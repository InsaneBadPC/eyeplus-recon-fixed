# Eyeplus Recon – nástroj pro průzkum kamery

Komplexní Python-nástroj pro reconnaissance a průzkum PTZ IP kamery **EYEPLUS_DEV**
(Ginatex / Goke čip, iCam365 cloud).

## 📦 Co odkryje

| Oblast               | Co dělá |
|----------------------|---------|
| **Detekce**          | ARP scan podle MAC vendoru `24:72:60`, port scan klíčových portů |
| **Služby**           | HTTP, ONVIF, RTSP, vcom-tunnel (8001), telnet, ssh |
| **Skrytá nastavení** | 52 známých CGI/REST endpointů (Ginatex, HiSilicon, iCam365, XMEye) |
| **ONVIF**            | GetDeviceInformation, GetCapabilities, GetProfiles, GetStreamUri |
| **PTZ**              | ONVIF i111.RelativeMove + HTTP fallback `/decoder_control.cgi` |
| **RTSP**             | DESCRIBE test hlavního i sub proudu |
| **Traffic sniff**    | DNS dotazy kamery, odchozí TCP spojení, cloud detekce (scapy) |
| **Report**           | Export do JSON a Markdown |

## 🚀 Jak spustit

```bash
cd /workspace/kamera

# 1. Instalace závislostí
pip3 install requests scapy

# 2. Automatický průzkum (všechno)
sudo python3 eyeplus_recon.py --auto

# 3. Konkrétní IP (kamera e.g. 172.20.94.172)
sudo python3 eyeplus_recon.py --ip 172.20.94.172

# 4. Pasivní odposlech 60s – zjistíš kam kamera telefoní
sudo python3 eyeplus_recon.py --ip 172.20.94.172 --sniff 60

# 5. PTZ ovládání
python3 eyeplus_recon.py --ip 172.20.94.172 --ptz up       # nahoru
python3 eyeplus_recon.py --ip 172.20.94.172 --ptz down
python3 eyeplus_recon.py --ip 172.20.94.172 --ptz left
python3 eyeplus_recon.py --ip 172.20.94.172 --ptz stop

# 6. RTSP jen test
python3 eyeplus_recon.py --ip 172.20.94.172 --rtsp-only

# 7. Interaktivní menu
sudo python3 eyeplus_recon.py --ip 172.20.94.172 --gui
```

> **`sudo` je potřeba** pro ARP scan a packet sniffing. Bez něj ti zbyde jen HTTP/ONVIF/RTSP.

## 🔍 Hlavní příkazy v menu (`--gui`)

1. **CGI endpointy** → vypíše všechny dostupné HTTP endpointy
2. **CGI param dump** → rozbije `param.cgi` odpovědi: server info, uživatelé, DDNS, wifi, NTP, motion atd.
3. **ONVIF GetDeviceInformation** → firmware verze, serial, manufacturer
4. **ONVIF GetProfiles + StreamUri** → RTSP adresy profilů
5. **PTZ pohyb** → směry + zoom
6. **RTSP test** → overí, že streamy fungují
7. **Sniff 30s** → zachytí DNS dotazy a odchozí TCP (kde kamera "volá domů")
8. **Telnet** → zkusí se připojit na port 23 (debug konzole)
9. **Export report** → JSON + Markdown soubor

## 📡 Skenované skryté endpointy (výběr)

```
/cgi-bin/hi3510/param.cgi?cmd=getserverinfo   → server info (verze, firmware)
/cgi-bin/hi3510/param.cgi?cmd=getuserinfo      → seznam uživatelů + hesel (v plain textu!)
/cgi-bin/hi3510/param.cgi?cmd=getnetinfo       → IP, maska, GW, DNS
/cgi-bin/hi3510/param.cgi?cmd=getwireless      → Wi-Fi SSID a heso
/cgi-bin/hi3510/param.cgi?cmd=getddns          → DDNS provider
/cgi-bin/hi3510/param.cgi?cmd=getmotion         → nastavení detekce pohybu
/cgi-bin/hi3510/param.cgi?cmd=getptz            → PTZ limity a rychlosti
/cgi-bin/hi3510/param.cgi?cmd=getvideoattr      → rozlišení, FPS, bitrate
/cgi-bin/hi3510/param.cgi?cmd=getimage          → gain, exposure, IR-CUT
/cgi-bin/hi3510/param.cgi?cmd=getplatform       → částečky čipu/platforma
/cgi-bin/hi3510/param.cgi?cmd=getall            → pokus o všechny parametry najednou
```

## ⚠️ Bezpečnostní upozornění

- Nástroj je určen **pouze pro váš vlastní hardware**.
- Výchozí hesla jsou běžně známá → **okamžitě změň** v web rozhraní kamery.
- Telnet/SSH a CGI endpointy mohou odhalit firmy konfigurace.
- Sniffování odhalí, komu kamera posílá data (iCam365 cloud).
