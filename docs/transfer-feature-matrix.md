# Transfer Feature Matrix

Local comparison material:

- `libgphoto2-2.5.34`: storage enumeration, PTP object handles, object info, object property list, retry/fallback behavior.
- `build/decoded-olympus-image-share`: Olympus HTTP CGI image list, thumbnail, screennail, full image download, Share Order strings.
- `OM Capture`: wireless tethering names such as `mtpip_scan`, `DownloadContentList`, and `DownloadLastCapturedImage`.

## Implemented Paths

| Area | Current implementation |
| --- | --- |
| USB PTP session | `PtpSession` now depends on `PtpTransport`, not USB-specific transport. |
| USB transport | `PtpUsbConnection` implements `PtpTransport` and keeps existing USB init/reset/event behavior. |
| MTP/IP transport | `MtpIpTransport` implements PTP/IP command, data, response, and event sockets over TCP port 15740. |
| MTP/IP discovery | Same-Wi-Fi hosts are probed after HTTP LAN discovery fails. |
| MTP/IP library | Uses shared `PtpSession` for `GetDeviceInfo`, `OpenSession`, `GetStorageIDs`, `GetObjectHandles`, `GetObjectInfo`, and object property reads. |
| MTP/IP thumbnails | Uses `GetThumb`; selected preview can fall back to downloading a bounded full object. |
| MTP/IP import | Download requests serialize exact host, port, and object handle. |
| HTTP AP | Existing OI.Share-style `get_imglist.cgi`, `get_thumbnail.cgi`, `get_screennail.cgi`, and full path download remain available. |
| HTTP LAN | Same-Wi-Fi HTTP base URL is kept separate from camera AP base URL and passed into the download service. |
| Auto import | HTTP LAN uses list polling; MTP/IP checks event socket first and then polls the object list. |
| 5-star filter | HTTP metadata and PTP `Rating` property / keyword metadata feed the same `CameraImage.rating`. |
| Share Order filter | HTTP metadata and PTP keyword metadata feed the same `CameraImage.isCameraSelected`. |
| Manual import | USB and MTP/IP requests carry exact handles; HTTP requests carry exact full paths. |

## Remaining Hardware Validation

- Confirm the OM-1 accepts the generic PTP/IP initiator handshake.
- Confirm the OM-1 sends ObjectAdded or Olympus DirectStoreImage events on the MTP/IP event socket.
- Confirm OM Capture's `DownloadContentList` does not require a vendor-specific pre-command before standard object listing.
- Confirm 5-star and Share Order are exposed on OM-1 over MTP/IP either as object properties or keywords.
- Confirm MTP/IP transfer speed against HTTP AP and HTTP LAN on the same router.
