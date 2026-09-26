# iOS Signing & Bundle ID Configuration

## 1. Register the Bundle ID

1. Go to [developer.apple.com](https://developer.apple.com) → **Certificates, Identifiers & Profiles**.
2. Under **Identifiers**, click **+** and create a new App ID with:
   - **Bundle ID:** `com.aiassistant.flutter`  
     *(change this to your reverse-domain format, e.g. `com.yourcompany.aiassistant`)*
3. Enable the **Push Notifications** and **Keychain Sharing** capabilities.

---

## 2. Create a Distribution Certificate

```bash
# On your Mac (requires Keychain access)
# Open Xcode → Preferences → Accounts → your Apple ID → Manage Certificates
# Click (+) → Apple Distribution
```

Or use the **Developer portal** → Certificates → Create → Apple Distribution.

Export the certificate as a `.p12` file:
```
Keychain Access → My Certificates → right-click → Export → .p12
```

---

## 3. Create a Provisioning Profile

1. Developer portal → **Profiles** → **+** → **App Store** (or Ad Hoc for TestFlight).
2. Select your **App ID** (`com.aiassistant.flutter`).
3. Select your **Distribution Certificate**.
4. Name it exactly: `AI Assistant Distribution`  
   *(must match the name in `ios/ExportOptions.plist`)*
5. Download and double-click to install.

---

## 4. Update ExportOptions.plist

Edit `ios/ExportOptions.plist` and replace:

| Placeholder | Your value |
|-------------|-----------|
| `YourTeamID` | Your 10-character Team ID from developer.apple.com → Membership |
| `com.aiassistant.flutter` | Your actual Bundle ID |
| `AI Assistant Distribution` | Your provisioning profile name (must be exact) |

---

## 5. Update Info.plist Bundle ID

Edit `ios/Runner/Info.plist`:
```xml
<key>CFBundleIdentifier</key>
<string>com.aiassistant.flutter</string>   <!-- replace with yours -->
```

---

## 6. Build a release IPA

```bash
# Ensure correct flutter version
flutter --version

# Build the release IPA (manual signing)
flutter build ipa \
  --release \
  --export-options-plist=ios/ExportOptions.plist

# Output: build/ios/ipa/ai_assistant_flutter.ipa
```

---

## 7. GitHub Actions CI/CD

Store these as **repository secrets**:

| Secret name | Contents |
|-------------|---------|
| `IOS_CERTIFICATE_P12` | `base64` of the `.p12` distribution certificate |
| `IOS_CERTIFICATE_PASS` | Password you set when exporting the `.p12` |
| `IOS_PROVISION_PROFILE` | `base64` of the `.mobileprovision` file |
| `IOS_TEAM_ID` | Your 10-character Team ID |

CI workflow snippet:
```yaml
- name: Import signing certificate
  env:
    IOS_CERTIFICATE_P12:   ${{ secrets.IOS_CERTIFICATE_P12 }}
    IOS_CERTIFICATE_PASS:  ${{ secrets.IOS_CERTIFICATE_PASS }}
  run: |
    echo "$IOS_CERTIFICATE_P12" | base64 --decode > /tmp/cert.p12
    security create-keychain -p "" build.keychain
    security import /tmp/cert.p12 \
      -k build.keychain \
      -P "$IOS_CERTIFICATE_PASS" \
      -T /usr/bin/codesign
    security set-keychain-settings build.keychain
    security unlock-keychain -p "" build.keychain
    security list-keychains -s build.keychain

- name: Install provisioning profile
  env:
    IOS_PROVISION_PROFILE: ${{ secrets.IOS_PROVISION_PROFILE }}
  run: |
    mkdir -p ~/Library/MobileDevice/Provisioning\ Profiles
    echo "$IOS_PROVISION_PROFILE" | base64 --decode \
      > ~/Library/MobileDevice/Provisioning\ Profiles/profile.mobileprovision

- name: Build IPA
  run: |
    flutter build ipa \
      --release \
      --export-options-plist=ios/ExportOptions.plist
```

---

## 8. FCM push notification setup for iOS

1. In Firebase Console → your project → **Project Settings** → **Cloud Messaging**.
2. Under **Apple app configuration**, upload your **APNs Auth Key** (`.p8`):
   - Create at developer.apple.com → Keys → Create → Apple Push Notifications service (APNs).
   - Enter your Team ID and Key ID.
3. Place `GoogleService-Info.plist` in `flutter/ios/Runner/`.
4. In Xcode, enable **Push Notifications** under your target's **Signing & Capabilities**.
5. Add `firebase_messaging: ^15.x.x` to `pubspec.yaml`.
6. Replace the stub `_getToken()` in `fcm_token_service.dart` per its doc comment.

---

## 9. Minimum iOS version

The app targets **iOS 12.0+** (set in `ios/Podfile`).  
`flutter_secure_storage` requires **iOS 12.0+**.  
`firebase_messaging` requires **iOS 11.0+** (satisfied).
