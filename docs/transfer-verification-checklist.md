# Transfer Verification Checklist

Scope: OM-1 / OM System transfer flows on Android 12 or newer.

## Before Testing

- Enable verbose app logs and keep a screen recording if possible.
- Prepare a card with at least 2000 mixed JPEG + RAW files.
- Mark several files with 5-star rating in camera.
- Mark several files with Share Order / camera selection if the body exposes it.
- Note camera body, firmware version, phone model, Android version, card slot, and file counts.

## USB Library

- Connect camera by USB and open Transfer.
- Confirm the initial quick list appears, then expands to the full library.
- Confirm JPEG + RAW pairs both appear, not only the last 3 images.
- Confirm partial storage failures do not abort the whole list.
- Confirm black thumbnails are replaced after thumbnail retry or selected-preview fallback.
- Select a non-recent file and import only that selected file.
- Select multiple mixed JPEG + RAW files and import the exact selection.
- Filter by 5-star and confirm only rated files are shown.
- Filter by Share Order and confirm only camera-selected files are shown.

## Wi-Fi AP HTTP

- Connect phone directly to the camera Wi-Fi AP, normally `192.168.0.10`.
- Load the library through `get_imglist.cgi`.
- Confirm thumbnails use `get_thumbnail.cgi` first and preview fallback when needed.
- Download one JPEG, one RAW, and one movie if present.
- Confirm the download service receives the AP base URL and does not use a stale LAN URL.

## Same Wi-Fi HTTP LAN

- Put phone and camera on the same infrastructure Wi-Fi network.
- Press Refresh from the app while connected to that router.
- Confirm the app probes the LAN and discovers the camera HTTP endpoint.
- Load the library, batch download 20+ stills, and compare speed against camera AP mode.
- Switch back to camera AP and confirm the LAN base URL is cleared.

## MTP/IP Wireless Tether

- Enable OM wireless tethering mode from the camera.
- Put phone and camera on the same infrastructure Wi-Fi network.
- Press Refresh and confirm MTP/IP discovery on TCP port 15740 if HTTP LAN is not available.
- Load the camera library over MTP/IP.
- Confirm thumbnails are fetched by PTP `GetThumb`.
- Select one older file and import only that object handle.
- Select several files and confirm exact selected handles are imported.
- Confirm delete is not offered for MTP/IP until camera-side delete is explicitly supported.

## Wireless Auto Import

- Enable Auto import in Settings.
- Set timing to `manual`; confirm no automatic transfer starts.
- Set timing to `on_connect`; connect and confirm only eligible new files import.
- Set timing to `since_launch`; take a new photo and confirm older files are skipped.
- During MTP/IP tethering, take a new photo.
- Confirm event socket detection imports the new object.
- Repeat after blocking/delaying events; confirm image-list polling fallback imports the new object.

## Log Capture

Record these log markers when something fails:

- `LAN wireless tether camera discovered`
- `MTP/IP wireless tether camera discovered`
- `MTP/IP ObjectAdded event`
- `MTP/IP wireless tether auto-import detected`
- `GetObjectHandles`
- `GetObjectInfo`
- `GetObjectPropList`
- `Skipping failed USB thumbnail`
- `Skipping failed MTP/IP thumbnail`
- `Preparing background download`

For each failure, capture:

- Exact step name from this checklist.
- Expected file count and displayed file count.
- File names selected for manual import.
- Files actually saved to the phone.
- Whether the issue happened on USB, Wi-Fi AP HTTP, same-Wi-Fi HTTP LAN, or MTP/IP.
