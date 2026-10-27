# NordVPN on the Bravia (owner's steps)

NordVPN stays on all the time, on a UK server. These apps skip it, so they see your home connection:

| Skip the VPN | Why |
|---|---|
| **Norton TV** (`com.haznorty.nortontv`) | Hue lights (home network), weather and scores |
| **HbbTV launcher** (`com.sony.dtv.hbbtvlauncher`) | BBC iPlayer, ITVX, Channel 4 and My5 run inside it on this TV, and they block VPNs |

1. Open **NordVPN** (the shield button at the top right of Norton TV, or All apps) and sign in.
2. Go to **Settings → Split tunnelling**, turn it **on**, choose **Bypass VPN for selected apps**, and pick
   **Norton TV** and **HbbTV launcher** (shown as `com.sony.dtv.hbbtvlauncher` if it has no name).
   If the list doesn't show system apps, look for a "Show system apps" switch.
3. In **Settings**, turn on **Auto-connect** (Always), and **Local network discovery** / **LAN** if offered.
4. Pick **United Kingdom** and connect. Norton TV's shield button turns **green**.

Check afterwards: iPlayer plays from the dock, the Lights row still switches bulbs, and the laptop can still reach
the TV (`adb connect 192.168.1.12`). If iPlayer complains about a VPN, its exclusion didn't take: repeat step 2.
