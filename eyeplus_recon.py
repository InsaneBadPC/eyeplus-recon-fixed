#!/usr/bin/env python3
"""
EYEPLUS / Ginatex IP Camera Reconnaissance Tool
================================================
Komplexni naradlo pro prubezkum PTZ IP kamery EYEPLUS (Ginatex / iCam365):
  - detekce kamery v siti podle MAC vendoru 24:72:60 (nebo ručně zadane IP)
  - skenovani sluzeb (HTTP, RTSP, ONVIF, vcom-tunnel/8001)
  - objevovani skrytych CGI endpointu (Ginatex / HiSilicon styl)
  - zachytavani provozu pomocí scapy (DNS dotazy kamery, odchozi spojeni)
  - PTZ ovladani (ONVIF + HTTP fallback)
  - RTSP test + ulozeni screenshotu
  - export reportu do JSON + Markdown

Pouzití:
  python3 eyeplus_recon.py --auto                # autodetekce + plny prubezkum
  python3 eyeplus_recon.py --ip 172.20.94.172    # konkretni IP
  python3 eyeplus_recon.py --ip <IP> --ptz up    # jednorazovy PTZ prikaz
  python3 eyeplus_recon.py --sniff 30            # pasivne sniffe 30 s
  python3 eyeplus_recon.py --gui                 # jednoduche textove menu
"""

import argparse
import base64
import binascii
import datetime
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

try:
    import requests
    from requests.auth import HTTPDigestAuth
except ImportError:
    print("Chybi 'requests' - pip install requests")
    sys.exit(1)

try:
    from scapy.all import (
        ARP, Ether, IP, UDP, TCP, DNS, DNSQR, DNSRR, ICMP,
        sniff, conf, srp, sr1
    )
    SCAPY_OK = True
except Exception as e:
    SCAPY_OK = False
    print(f"[!] scapy nedostupne ({e}); pasivni odposlech bude omezeny")

# ---------------------------------------------------------------------------
# Konstanty
# ---------------------------------------------------------------------------
EYEPLUS_MAC_PREFIXES = ("24:72:60", "00:12:12", "00:12:17", "9c:8e:cd", "00:02:d1")
DEFAULT_CREDS = [
    ("admin", "admin"),
    ("admin", ""),
    ("admin", "12345"),
    ("admin", "888888"),
    ("root", "root"),
    ("user", "user"),
]

HIDDEN_ENDPOINTS = [
    ("/", "GET", None, "web root"),
    ("/index.asp", "GET", None, "web UI"),
    ("/index.htm", "GET", None, "web UI alt"),
    ("/login.asp", "GET", None, "login page"),
    ("/onvif/device_service", "POST", """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <soap:Body><tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities></soap:Body>
</soap:Envelope>""", "ONVIF GetCapabilities"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getserverinfo", "GET", None, "Ginatex server info"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getnetinfo", "GET", None, "Ginatex net info"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getuserinfo", "GET", None, "Ginatex users"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getwireless", "GET", None, "Ginatex wifi"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getddns", "GET", None, "Ginatex DDNS"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getupnp", "GET", None, "Ginatex UPnP"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getntp", "GET", None, "Ginatex NTP"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getplatform", "GET", None, "Ginatex platform"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getfatal", "GET", None, "Ginatex fatals"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getrecord", "GET", None, "Ginatex record"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getmotion", "GET", None, "Ginatex motion"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getvideoattr", "GET", None, "Ginatex video"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getaudio", "GET", None, "Ginatex audio"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getimage", "GET", None, "Ginatex image"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getoverlay", "GET", None, "Ginatex overlay"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getir", "GET", None, "Ginatex IR"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getptz", "GET", None, "Ginatex PTZ"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getalarm", "GET", None, "Ginatex alarm"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getdevtype", "GET", None, "Ginatex devtype"),
    ("/cgi-bin/hi3510/param.cgi?cmd=getversion", "GET", None, "Ginatex version"),
    ("/cgi-bin/hi3510/param.cgi?cmd=gettime", "GET", None, "Ginatex time"),
    ("/cgi-bin/param.cgi?cmd=getserverinfo", "GET", None, "HiSilicon server info"),
    ("/cgi-bin/param.cgi?cmd=getnetinfo", "GET", None, "HiSilicon net info"),
    ("/api/v1/system/info", "GET", None, "REST system"),
    ("/api/v1/system/status", "GET", None, "REST status"),
    ("/api/v1/stream/main", "GET", None, "REST main stream"),
    ("/api/v1/stream/sub", "GET", None, "REST sub stream"),
    ("/api/v1/ptz/move", "GET", None, "REST PTZ"),
    ("/system.ini", "GET", None, "system ini"),
    ("/config.ini", "GET", None, "config ini"),
    ("/deviceinfo", "GET", None, "device info"),
    ("/status.xml", "GET", None, "status xml"),
    ("/get_params.cgi", "GET", None, "get params"),
    ("/get_status.cgi", "GET", None, "get status"),
    ("/decoder_control.cgi?command=1", "GET", None, "PTZ stop"),
    ("/decoder_control.cgi?command=0", "GET", None, "PTZ up"),
    ("/vcom/register", "GET", None, "vcom register"),
    ("/vcom/tunnel", "GET", None, "vcom tunnel"),
    ("/vcom/heartbeat", "GET", None, "vcom heartbeat"),
    ("/firmware.bin", "GET", None, "firmware bin"),
    ("/update.bin", "GET", None, "firmware update"),
    ("/backup", "GET", None, "backup"),
    ("/shell", "GET", None, "debug shell"),
    ("/debug", "GET", None, "debug"),
    ("/test", "GET", None, "test"),
    ("/admin", "GET", None, "admin"),
    ("/hidden", "GET", None, "hidden"),
]

PORTS = {
    80:    "HTTP / ONVIF",
    554:   "RTSP",
    8001:  "vcom-tunnel (iCam365 cloud)",
    8000:  "HTTP alt / SDK",
    8080:  "HTTP alt",
    8888:  "HTTP alt",
    443:   "HTTPS",
    34567: "vstarcam/XMEye",
    34599: "XMEye register",
    23:    "Telnet",
    22:    "SSH",
    53:    "DNS",
}

PTZ_DIR = {
    "up":    ("0", 0.1),
    "down":  ("2", 0.1),
    "left":  ("4", 0.1),
    "right": ("6", 0.1),
    "stop":  ("1", 0.0),
    "upleft":    ("90", 0.1),
    "upright":   ("91", 0.1),
    "downleft":  ("92", 0.1),
    "downright": ("93", 0.1),
    "zoomin":  ("8", 0.1),
    "zoomout": ("9", 0.1),
}

# ---------------------------------------------------------------------------
# Utility
# ---------------------------------------------------------------------------

class C:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    CYAN = "\033[36m"

    @staticmethod
    def _on():
        return sys.stdout.isatty()

    @classmethod
    def paint(cls, color, text):
        if not cls._on():
            return text
        return f"{color}{text}{cls.RESET}"


def log(level, msg):
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    color = {
        "info":  C.CYAN,
        "ok":    C.GREEN,
        "warn":  C.YELLOW,
        "err":   C.RED,
        "hdr":   C.MAGENTA,
        "dim":   C.DIM,
    }.get(level, "")
    print(f"{C.DIM}[{ts}]{C.RESET} {C.paint(color, msg)}")


def hr(title="", char="-", width=78):
    if title:
        pad = max(0, (width - len(title) - 2) // 2)
        s = char * pad + f" {title} " + char * pad
        print(C.paint(C.BOLD, s[:width]))
    else:
        print(char * width)


def tcp_open(host, port, timeout=1.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def get_local_subnet():
    try:
        out = subprocess.check_output(["ip", "-4", "addr"], text=True)
    except Exception:
        return "192.168.1.0/24"
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("inet "):
            parts = line.split()
            ip = parts[1]
            if not ip.startswith("127.") and "/" in ip:
                a, b, c, _ = ip.split("/")[0].split(".")
                return f"{a}.{b}.{c}.0/24"
    return "192.168.1.0/24"


# ---------------------------------------------------------------------------
# 1) Detekce kamery v siti
# ---------------------------------------------------------------------------

def arp_scan(subnet, timeout=2.0):
    if not SCAPY_OK:
        return []
    try:
        conf.verb = 0
        ans, _ = srp(
            Ether(dst="ff:ff:ff:ff:ff:ff") / ARP(pdst=subnet),
            timeout=timeout, retry=1
        )
        results = []
        for snd, rcv in ans:
            results.append({"ip": rcv.psrc, "mac": rcv.hwsrc})
        return results
    except PermissionError:
        log("warn", "ARP scan vyuzaduje root - preskoceno")
        return []
    except Exception as e:
        log("warn", f"ARP scan selhal: {e}")
        return []


def port_scan_host(ip, ports=PORTS.keys(), timeout=0.6):
    open_ports = {}
    ports_list = list(ports)
    with ThreadPoolExecutor(max_workers=min(16, len(ports_list))) as ex:
        futs = {ex.submit(tcp_open, ip, p, timeout): p for p in ports_list}
        for f in as_completed(futs):
            p = futs[f]
            try:
                if f.result():
                    open_ports[p] = PORTS.get(p, "?")
            except Exception:
                pass
    return open_ports


def ping_sweep(subnet, timeout=0.4):
    base = subnet.split("/")[0]
    parts = base.split(".")
    prefix = ".".join(parts[:3]) + "."
    found = []
    def ping(i):
        ip = f"{prefix}{i}"
        try:
            r = subprocess.run(["ping", "-c1", "-W1", ip],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2)
            if r.returncode == 0:
                return ip
        except Exception:
            pass
        return None
    with ThreadPoolExecutor(max_workers=64) as ex:
        for r in ex.map(ping, range(1, 255)):
            if r:
                found.append({"ip": r, "mac": arp_lookup(r) or "??"})
    return found


def arp_lookup(ip):
    try:
        out = subprocess.check_output(["ip", "neigh", "show", ip], text=True)
        m = re.search(r"([0-9a-fA-F:]{17})", out)
        if m:
            return m.group(1).lower()
    except Exception:
        pass
    return None


def detect_cameras(subnet=None, target_mac_prefix="24:72:60", manual_ip=None):
    nalezene = []
    if manual_ip:
        log("info", f"Kontrola rucne zadane IP {manual_ip}...")
        mac = arp_lookup(manual_ip) or "??:??:??:??:??:??"
        nalezene.append({"ip": manual_ip, "mac": mac, "manual": True})
    else:
        if not subnet:
            subnet = get_local_subnet()
        log("info", f"ARP scan {subnet}...")
        hosts = arp_scan(subnet)
        if not hosts:
            log("warn", "ARP scan nic nevratel (chybi root). Zkusim ping sweep...")
            hosts = ping_sweep(subnet)
        log("ok", f"ARP scan: {len(hosts)} hostu")
        for h in hosts:
            mac = (h.get("mac") or "").lower()
            ip  = h["ip"]
            if mac.startswith(tuple(p.lower() for p in EYEPLUS_MAC_PREFIXES)):
                nalezene.append({"ip": ip, "mac": mac})

    final = []
    for cand in nalezene:
        ip = cand["ip"]
        log("info", f"Kontrola portu {ip}...")
        ports = port_scan_host(ip)
        cand["open_ports"] = ports
        cam_score = 0
        if 80 in ports:    cam_score += 1
        if 554 in ports:   cam_score += 2
        if 8001 in ports:  cam_score += 4
        if 34567 in ports: cam_score += 3
        if 34599 in ports: cam_score += 3
        cand["score"] = cam_score
        if cam_score > 0 or cand.get("manual"):
            final.append(cand)
    final.sort(key=lambda x: -x.get("score", 0))
    return final


# ---------------------------------------------------------------------------
# 2) HTTP / skryta nastaveni
# ---------------------------------------------------------------------------

class CameraClient:
    def __init__(self, ip, user="admin", password="admin", timeout=4):
        self.ip = ip
        self.user = user
        self.password = password
        self.timeout = timeout
        self.session = requests.Session()
        self.session.auth = (user, password)
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) EyeplusRecon/1.0",
        })
        self.base = f"http://{ip}"
        self.found_endpoints = []
        self.cgi_dump = {}

    def get(self, path, **kw):
        try:
            r = self.session.get(self.base + path, timeout=self.timeout,
                                 allow_redirects=False, **kw)
            return r
        except Exception as e:
            return _FakeResp(error=e)

    def post(self, path, data=None, **kw):
        try:
            r = self.session.post(self.base + path, data=data, timeout=self.timeout,
                                  allow_redirects=False, **kw)
            return r
        except Exception as e:
            return _FakeResp(error=e)

    def test_creds(self):
        for u, p in DEFAULT_CREDS:
            try:
                r = requests.get(self.base + "/", auth=(u, p), timeout=2)
                if r.status_code != 401:
                    self.user, self.password = u, p
                    self.session.auth = (u, p)
                    log("ok", f"Prihlaseni OK: {u}:{p}")
                    return (u, p)
            except Exception:
                pass
        log("warn", "Zadne vychodni heslo nefungovalo, zkousim admin:admin")
        return (self.user, self.password)

    def probe_endpoints(self):
        log("info", "Prochazim poznane CGI / skryte endpointy...")
        findings = []
        for path, method, body, note in HIDDEN_ENDPOINTS:
            try:
                if method == "GET":
                    r = self.get(path)
                else:
                    r = self.post(path, data=body,
                                  headers={"Content-Type": 'application/soap+xml; charset=utf-8',
                                           "SOAPAction": '"http://www.onvif.org/ver10/device/wsdl/GetCapabilities"'})
                if r is None:
                    continue
                code = getattr(r, "status_code", 0)
                size = len(getattr(r, "content", b"") or b"")
                interesting = (code in (200, 301, 302) and size > 0) or (code == 401)
                if interesting:
                    snippet = (r.content[:200].decode(errors="replace") if r.content else "")
                    rec = {"path": path, "method": method, "code": code,
                           "size": size, "note": note, "snippet": snippet}
                    findings.append(rec)
                    if path.startswith("/cgi-bin/hi3510/param.cgi"):
                        self._parse_param_cgi(path, r.text if hasattr(r, "text") else snippet)
            except Exception:
                pass
        self.found_endpoints = findings
        return findings

    def _parse_param_cgi(self, path, body):
        if not body:
            return
        m = re.search(r"cmd=([a-zA-Z]+)", path)
        cmd = m.group(1) if m else path
        params = {}
        for tok in re.split(r"[;\n\r]+", body):
            if "=" in tok:
                k, v = tok.split("=", 1)
                params[k.strip()] = v.strip()
        if params:
            self.cgi_dump[cmd] = params

    def discover_files(self):
        paths = [
            "/config.bin", "/config.cfg", "/backup.bin", "/backup.cfg",
            "/mnt/mtd/config", "/mnt/mtd/user", "/etc/passwd", "/etc/shadow",
            "/var/log/messages", "/var/log/syslog", "/tmp/log", "/proc/version",
            "/system.dat", "/user.dat", "/cloud.cfg",
        ]
        out = {}
        for p in paths:
            try:
                r = self.get(p)
                code = getattr(r, "status_code", 0)
                size = len(getattr(r, "content", b"") or b"")
                if code == 200 and size > 0:
                    snip = r.content[:300].decode(errors="replace")
                    out[p] = {"code": code, "size": size, "snippet": snip}
            except Exception:
                pass
        return out

    def onvif_get_capabilities(self):
        body = HIDDEN_ENDPOINTS[4][2]
        r = self.post("/onvif/device_service", data=body,
                      headers={"Content-Type": 'application/soap+xml; charset=utf-8'})
        return getattr(r, "text", "")

    def onvif_get_device_info(self):
        body = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <soap:Body><tds:GetDeviceInformation/></soap:Body>
</soap:Envelope>"""
        r = self.post("/onvif/device_service", data=body,
                      headers={"Content-Type": 'application/soap+xml; charset=utf-8'})
        return getattr(r, "text", "")

    def onvif_get_profiles(self):
        body = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
  <soap:Body><trt:GetProfiles/></soap:Body>
</soap:Envelope>"""
        r = self.post("/onvif/media_service", data=body,
                      headers={"Content-Type": 'application/soap+xml; charset=utf-8'})
        return getattr(r, "text", "")

    def onvif_get_streams(self):
        profiles_xml = self.onvif_get_profiles()
        tokens = re.findall(r"Profiles[^>]*token=\"([^\"]+)\"", profiles_xml)
        if not tokens:
            tokens = re.findall(r'token="([^"]+)"', profiles_xml)
        streams = []
        for tok in tokens:
            body = f"""<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body><trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>{tok}</trt:ProfileToken></trt:GetStreamUri></soap:Body>
</soap:Envelope>"""
            r = self.post("/onvif/media_service", data=body,
                          headers={"Content-Type": 'application/soap+xml; charset=utf-8'})
            uri_m = re.search(r"<tt:Uri>([^<]+)</tt:Uri>", getattr(r, "text", ""))
            if uri_m:
                streams.append({"profile": tok, "uri": uri_m.group(1)})
        return streams

    def onvif_ptz_relative(self, x=0.0, y=0.0, profile=None):
        if profile is None:
            profs = self.onvif_get_profiles()
            m = re.search(r'token="([^"]+)"', profs)
            if m:
                profile = m.group(1)
            else:
                return False, "zony profil"
        body = f"""<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body><tptz:RelativeMove><tptz:ProfileToken>{profile}</tptz:ProfileToken><tptz:Translation><tt:PanTilt x="{x}" y="{y}"/></tptz:Translation></tptz:RelativeMove></soap:Body>
</soap:Envelope>"""
        r = self.post("/onvif/ptz_service", data=body,
                      headers={"Content-Type": 'application/soap+xml; charset=utf-8'})
        return (getattr(r, "status_code", 0) == 200), getattr(r, "text", "")

    def onvif_ptz_stop(self, profile=None):
        if profile is None:
            profs = self.onvif_get_profiles()
            m = re.search(r'token="([^"]+)"', profs)
            if m:
                profile = m.group(1)
            else:
                return False, "zony profil"
        body = f"""<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
  <soap:Body><tptz:Stop><tptz:ProfileToken>{profile}</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt></tptz:Stop></soap:Body>
</soap:Envelope>"""
        r = self.post("/onvif/ptz_service", data=body,
                      headers={"Content-Type": 'application/soap+xml; charset=utf-8'})
        return (getattr(r, "status_code", 0) == 200), getattr(r, "text", "")

    def http_ptz(self, command):
        r = self.get(f"/decoder_control.cgi?command={command}")
        return getattr(r, "status_code", 0), len(getattr(r, "content", b"") or b"")


class _FakeResp:
    def __init__(self, error=None):
        self.status_code = 0
        self.content = b""
        self.text = ""
        self.error = error
    def __bool__(self): return False


# ---------------------------------------------------------------------------
# 3) Pasivni sniffer
# ---------------------------------------------------------------------------

class Sniffer:
    def __init__(self, target_ip=None, target_mac=None):
        self.target_ip = target_ip
        self.target_mac = target_mac
        self.dns_queries = []
        self.outgoing = []
        self.arp_traffic = []
        self.cloud_hits = []

    def _is_target(self, pkt):
        if self.target_ip and IP in pkt:
            return pkt[IP].src == self.target_ip or pkt[IP].dst == self.target_ip
        if self.target_mac and Ether in pkt:
            return pkt[Ether].src.lower() == self.target_mac or pkt[Ether].dst.lower() == self.target_mac
        if IP in pkt:
            return pkt[IP].src == self.target_ip or pkt[IP].dst == self.target_ip
        return True

    def _is_cloud(self, qname):
        cloud_kw = ["icam365", "ginatex", "vcom", "vstarcam", "xmyun", "xiongmai",
                    "cloud", "push", "tuya", "aliyun", "amazonaws", "azure", "aliyuncs",
                    "platform", "relay", "p2p", "iot", "device"]
        return any(k in qname.lower() for k in cloud_kw)

    def handle(self, pkt):
        try:
            ts = datetime.datetime.now().isoformat(timespec="seconds")
            if Ether in pkt and ARP in pkt:
                if self._is_target(pkt):
                    self.arp_traffic.append({
                        "time": ts,
                        "op": pkt[ARP].op,
                        "psrc": pkt[ARP].psrc, "hwsrc": pkt[ARP].hwsrc,
                        "pdst": pkt[ARP].pdst, "hwdst": pkt[ARP].hwdst,
                    })
            if IP in pkt and UDP in pkt and DNS in pkt and pkt[DNS].qd is not None:
                if self._is_target(pkt):
                    try:
                        qname = pkt[DNS].qd.qname.decode(errors="replace").rstrip(".")
                        self.dns_queries.append({"time": ts, "src": pkt[IP].src, "qname": qname})
                        if self._is_cloud(qname):
                            self.cloud_hits.append({"time": ts, "src": pkt[IP].src, "qname": qname})
                    except Exception:
                        pass
            if IP in pkt and TCP in pkt:
                if self.target_ip and pkt[IP].src == self.target_ip:
                    self.outgoing.append({
                        "time": ts,
                        "dst_ip":   pkt[IP].dst,
                        "dst_port": pkt[TCP].dport,
                    })
                    if self._looks_cloud_port(pkt[TCP].dport):
                        self.cloud_hits.append({
                            "time": ts, "src": pkt[IP].src,
                            "dst": f"{pkt[IP].dst}:{pkt[TCP].dport}", "qname": "<TCP>"
                        })
        except Exception:
            pass

    @staticmethod
    def _looks_cloud_port(p):
        return p in (8001, 34567, 34599, 8883, 8884, 8443, 443)

    def run(self, duration=30):
        if not SCAPY_OK:
            log("err", "scapy neni - nelze sniffovat")
            return
        try:
            conf.verb = 0
            log("info", f"Pasivni odposlech {duration}s (target={self.target_ip or self.target_mac})...")
            sniff(filter="arp or (udp port 53) or tcp", prn=self.handle,
                  store=False, timeout=duration)
        except PermissionError:
            log("err", "Pasivni sniff vyuzaduje root - zkus sudo")
        except Exception as e:
            log("err", f"Sniff selhal: {e}")


# ---------------------------------------------------------------------------
# 4) RTSP test
# ---------------------------------------------------------------------------

def test_rtsp(ip, user="admin", password="admin", streams=("0/av0", "0/av1"), timeout=4):
    results = []
    for path in streams:
        url = f"rtsp://{user}:{password}@{ip}:554/{path}"
        try:
            s = socket.create_connection((ip, 554), timeout=timeout)
            req = (
                f"DESCRIBE {url} RTSP/1.0\r\n"
                f"CSeq: 1\r\n"
                f"User-Agent: EyeplusRecon/1.0\r\n"
                f"Accept: application/sdp\r\n\r\n"
            )
            s.sendall(req.encode())
            data = b""
            s.settimeout(timeout)
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
                if b"\r\n\r\n" in data and len(data) > 200:
                    break
            s.close()
            ok = b"RTSP/1.0 200" in data
            results.append({"url": url, "ok": ok, "size": len(data),
                            "snippet": data[:200].decode(errors="replace")})
        except Exception as e:
            results.append({"url": url, "ok": False, "error": str(e)})
    return results


# ---------------------------------------------------------------------------
# 5) PTZ jednorazove
# ---------------------------------------------------------------------------

def ptz_action(cam, direction, duration=0.4):
    if direction not in PTZ_DIR:
        log("err", f"Neznamy směr {direction}")
        return
    cmd, _ = PTZ_DIR[direction]
    log("info", f"PTZ {direction} pres HTTP fallback...")
    code, size = cam.http_ptz(cmd)
    log("ok" if code == 200 else "warn", f"  HTTP PTZ: HTTP {code} ({size} B)")
    if direction != "stop":
        time.sleep(duration)
        cam.http_ptz("1")


# ---------------------------------------------------------------------------
# 6) GUI (textove menu)
# ---------------------------------------------------------------------------

def gui_loop(cameras):
    if not cameras:
        log("err", "Zadne kamery - nejdrivev --auto nebo --ip")
        return
    cam_info = cameras[0]
    cam = CameraClient(cam_info["ip"])
    cam.test_creds()

    while True:
        print()
        hr("MENU")
        print("  1) Znovu projdi CGI endpointy")
        print("  2) Zobraz nalezena nastaveni (param.cgi)")
        print("  3) ONVIF GetDeviceInformation")
        print("  4) ONVIF GetCapabilities")
        print("  5) ONVIF GetProfiles + StreamUri")
        print("  6) PTZ: smer (up/down/left/right/zoomin/zoomout/stop)")
        print("  7) RTSP test (DESCRIBE)")
        print("  8) Pasivne sniffuj provoz 30 s")
        print("  9) Zkus telnet na port 23")
        print(" 10) Export reportu (JSON + Markdown)")
        print("  0) Konec")
        try:
            choice = input("> ").strip()
        except EOFError:
            break
        if choice == "1":
            cam.test_creds()
            cam.probe_endpoints()
            for f in cam.found_endpoints:
                print(f"  {f['code']:>3} {f['size']:>6}B  {f['method']:>4} {f['path']:50s}  {f['note']}")
        elif choice == "2":
            for cmd, params in cam.cgi_dump.items():
                hr(cmd)
                for k, v in params.items():
                    print(f"  {k} = {v}")
        elif choice == "3":
            print(cam.onvif_get_device_info()[:2000])
        elif choice == "4":
            print(cam.onvif_get_capabilities()[:2000])
        elif choice == "5":
            print(cam.onvif_get_profiles()[:1500])
            print("---")
            for s in cam.onvif_get_streams():
                print(f"  [{s['profile']}] {s['uri']}")
        elif choice == "6":
            d = input("smer > ").strip()
            ptz_action(cam, d)
        elif choice == "7":
            for r in test_rtsp(cam.ip, cam.user, cam.password):
                print(f"  {'OK' if r.get('ok') else 'FAIL'}  {r['url']}  {r.get('snippet','')[:100]}")
        elif choice == "8":
            s = Sniffer(target_ip=cam.ip)
            s.run(duration=30)
            hr("DNS dotazy kamery")
            for d in s.dns_queries:
                flag = "  [CLOUD]" if s._is_cloud(d["qname"]) else ""
                print(f"  {d['time']}  {d['src']:>15}  {d['qname']}{flag}")
            hr("Odchozi TCP")
            seen = set()
            for o in s.outgoing:
                key = (o["dst_ip"], o["dst_port"])
                if key in seen: continue
                seen.add(key)
                print(f"  {o['time']}  {cam.ip} -> {o['dst_ip']}:{o['dst_port']}")
            hr("Cloud hity")
            for c in s.cloud_hits:
                print(f"  {c}")
        elif choice == "9":
            if tcp_open(cam.ip, 23, 2.0):
                log("ok", "Telnet port 23 OTEVREN! (lze se pripojit)")
            else:
                log("warn", "Telnet port 23 zavreny")
        elif choice == "10":
            export_report(cam_info, cam, Sniffer())
        elif choice == "0":
            return


def export_report(cam_info, cam, sniffer):
    out = {
        "ip":  cam.ip,
        "user": cam.user,
        "open_ports": cam_info.get("open_ports", {}),
        "endpoints": cam.found_endpoints,
        "cgi_dump":  cam.cgi_dump,
        "onvif_device_info": cam.onvif_get_device_info(),
        "onvif_capabilities": cam.onvif_get_capabilities(),
        "onvif_profiles": cam.onvif_get_profiles(),
        "onvif_streams": cam.onvif_get_streams(),
        "rtsp": test_rtsp(cam.ip, cam.user, cam.password),
    }
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    jpath = f"report_{cam.ip}_{ts}.json"
    mpath = f"report_{cam.ip}_{ts}.md"
    Path(jpath).write_text(json.dumps(out, indent=2, ensure_ascii=False))
    md = [f"# Eyeplus recon - {cam.ip} - {ts}\n"]
    md.append(f"- IP: `{cam.ip}`")
    md.append(f"- User: `{cam.user}`")
    md.append(f"- Open ports: `{cam_info.get('open_ports')}`")
    md.append(f"\n## ONVIF Streams\n")
    for s in out["onvif_streams"]:
        md.append(f"- [{s['profile']}] `{s['uri']}`")
    md.append(f"\n## CGI dump\n")
    for c, params in cam.cgi_dump.items():
        md.append(f"### {c}\n")
        for k, v in params.items():
            md.append(f"- **{k}** = `{v}`")
    md.append(f"\n## HTTP endpointy ({len(cam.found_endpoints)})\n")
    for f in cam.found_endpoints:
        md.append(f"- `{f['code']}` {f['method']} `{f['path']}` ({f['size']}B) - {f['note']}")
    Path(mpath).write_text("\n".join(md))
    log("ok", f"Report ulozen: {jpath} a {mpath}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Eyeplus/Ginatex IP kamera recon",
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ip", help="IP adresa kamery (preskoci autodetekci)")
    ap.add_argument("--subnet", help="CIDR pro scan (vychozi auto)")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--auto", action="store_true", help="autodetekce + plny scan")
    ap.add_argument("--gui", action="store_true", help="interaktivni menu")
    ap.add_argument("--sniff", type=int, default=0, help="pasivne sniffovat N sekund")
    ap.add_argument("--ptz", help="jednorazovy PTZ prikaz: up|down|left|right|zoomin|zoomout|stop")
    ap.add_argument("--rtsp-only", action="store_true", help="pouze RTSP test")
    ap.add_argument("--port-scan-only", action="store_true", help="pouze TCP port scan")
    ap.add_argument("--no-color", action="store_true")
    args = ap.parse_args()

    if args.no_color:
        C._on = lambda: False

    if args.rtsp_only and args.ip:
        for r in test_rtsp(args.ip, args.user, args.password):
            print(f"{'OK' if r.get('ok') else 'FAIL'}  {r['url']}  {r.get('snippet','')[:100]}")
        return

    cameras = detect_cameras(subnet=args.subnet, manual_ip=args.ip)
    if not cameras:
        log("err", "Kamera nenalezena. Zadej --ip rucne.")
        return

    log("hdr", f"Nalezeno {len(cameras)} podezrelych kamer:")
    for c in cameras:
        log("ok", f"  IP={c['ip']}  MAC={c.get('mac')}  score={c.get('score')}  ports={c.get('open_ports')}")

    if args.port_scan_only:
        return

    if args.sniff > 0:
        s = Sniffer(target_ip=cameras[0]["ip"])
        s.run(duration=args.sniff)
        hr("DNS dotazy")
        for d in s.dns_queries:
            print(f"  {d['time']}  {d['src']:>15}  {d['qname']}{'  [CLOUD]' if s._is_cloud(d['qname']) else ''}")
        hr("Odchozi TCP")
        seen = set()
        for o in s.outgoing:
            key = (o["dst_ip"], o["dst_port"])
            if key in seen: continue
            seen.add(key)
            print(f"  {o['time']}  {cameras[0]['ip']} -> {o['dst_ip']}:{o['dst_port']}")
        hr("Cloud hity")
        for c in s.cloud_hits:
            print(f"  {c}")
        return

    cam_info = cameras[0]
    cam = CameraClient(cam_info["ip"], args.user, args.password)
    cam.test_creds()

    if args.ptz:
        ptz_action(cam, args.ptz)
        return

    if args.auto or not args.gui:
        log("hdr", "=== Port scan ===")
        for p, name in cam_info.get("open_ports", {}).items():
            log("ok", f"  TCP/{p:>5}  {name}")
        log("hdr", "=== HTTP / CGI endpointy ===")
        cam.probe_endpoints()
        for f in cam.found_endpoints:
            log("ok" if f["code"] == 200 else "warn",
                f"  {f['code']:>3} {f['size']:>6}B  {f['method']:>4} {f['path']:48s} {f['note']}")
        log("hdr", "=== CGI param dump (Ginatex/HiSilicon) ===")
        for c, params in cam.cgi_dump.items():
            for k, v in params.items():
                print(f"  {c}.{k} = {v}")
        log("hdr", "=== ONVIF ===")
        di = cam.onvif_get_device_info()
        print(di[:1500])
        for s in cam.onvif_get_streams():
            log("ok", f"  Stream [{s['profile']}]: {s['uri']}")
        log("hdr", "=== RTSP ===")
        for r in test_rtsp(cam.ip, cam.user, cam.password):
            log("ok" if r.get("ok") else "warn", f"  {'OK' if r.get('ok') else 'FAIL'}  {r['url']}")
        log("hdr", "=== Telnet / SSH ===")
        log("ok" if tcp_open(cam.ip, 23, 1.5) else "warn", "  Telnet (23) " + ("OTEVREN" if tcp_open(cam.ip, 23, 1.5) else "zavren"))
        log("ok" if tcp_open(cam.ip, 22, 1.5) else "warn", "  SSH (22) "    + ("OTEVREN" if tcp_open(cam.ip, 22, 1.5) else "zavren"))
        export_report(cam_info, cam, Sniffer())

    if args.gui:
        gui_loop(cameras)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[!] preruseno")
