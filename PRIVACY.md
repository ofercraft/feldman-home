# Privacy Policy

Last updated: August 2, 2026

Feldman Home is an independent Android client that connects directly to Home Assistant and, when configured, Frigate services chosen by the user.

## Data handling

Feldman Home does not include advertising, analytics, crash-reporting SDKs, or developer-operated servers. The app does not sell personal information or send Home Assistant data to the Feldman Home developer.

The app stores configuration on the device, including the Home Assistant URL and credential, optional Frigate URL, dashboard and widget settings, locally cached camera snapshots, and local diagnostic logs. Android cloud backup is disabled for the app so credentials and home configuration are not copied to cloud backup.

## Network communication

The app communicates with:

- the Home Assistant instance selected by the user;
- the Frigate instance selected by the user, if that feature is configured; and
- devices on the local network during user-requested Home Assistant discovery.

Demo camera imagery is bundled with the app and does not contact an image-hosting service. Data sent to a user-selected Home Assistant or Frigate instance is governed by that service and its operator.

## Permissions

Network and local-network permissions support Home Assistant and Frigate connections and discovery. Location or nearby-Wi-Fi permissions may be requested where Android requires them for local network discovery. Boot and foreground-service permissions keep configured widgets and mobile sensors updated. Biometric permission protects actions when the user enables authentication.

## Data deletion

Users can delete Feldman Home data through Android's app-info storage controls or by uninstalling the app. Data already sent to a configured Home Assistant or Frigate instance must be managed on that instance.

## Security

Users control their server URLs. HTTPS is recommended for remote connections. HTTP remains available for local Home Assistant installations; traffic over HTTP is not encrypted.

Questions about this policy can be opened as a GitHub issue. Security-sensitive reports should follow [SECURITY.md](SECURITY.md).