\# Final Project – Embedded IoT UDP Gateway



\## Overview

This project is an Android-based UDP Gateway for an Embedded IoT system.



The application receives audio recordings from a remote embedded device over UDP,

stores them locally, and exposes them through:

\- HTTP server (for media access)

\- WebSocket server (for control \& multi-user web interface)



Recordings can be played using \*\*VLC\*\* from both mobile and desktop devices.



---



\## Main Features (Stage A)

\- UDP file transfer (audio recordings)

\- HTTP server for media files

\- WebSocket server for control and status

\- Web-based client (HTML)

\- Multi-user support (OWNER / VIEWER)

\- VLC playback support

\- Android foreground service (stable background operation)



---



\## Architecture

\*\*Android Device = Gateway\*\*

\- Receives audio files over UDP

\- Saves recordings locally

\- Serves web UI and media over HTTP

\- Handles commands over WebSocket



\*\*Clients\*\*

\- Web browsers (PC / Mobile)

\- VLC Media Player



---



\## Network Ports

| Service | Port | Description |

|------|------|------------|

| UDP | 5000 | Receive audio files |

| WebSocket | 8090 | Control \& multi-user |

| HTTP | 8081 | Web UI \& media |



---



\## How to Run

1\. Install the app on an Android phone

2\. Start the UDP Gateway from the app

3\. Make sure phone and PC are on the \*\*same Wi-Fi network\*\*

4\. Open browser on PC or phone:

5\. Click \*\*Connect\*\* (WebSocket connects automatically)



---



\## Playing Recordings in VLC

1\. Copy the media URL from the web interface  

2\. Open VLC → Media → Open Network Stream  

3\. Paste the URL and press Play  



Supported format:

\- WAV

\- 48 kHz

\- Mono

\- 24-bit PCM



---



\## Multi-User Logic

\- First connected browser = \*\*OWNER\*\*

\- Other browsers = \*\*VIEWER\*\*

\- Only OWNER can start/stop recording

\- VIEWERS can see recordings and play media



---



\## Current Status

✅ Stage A completed  

❌ Live streaming (planned)  

❌ RAW UDP (Base64 still used)  

❌ Resume / checksum / retries (planned)



---



\## Authors

\- Eliran Zargari

\- Ido Agai



Final Project – Embedded IoT



