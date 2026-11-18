Hei Seed – AI Camera Assistant

Hei Seed is an open-source AI camera assistant built around the Seeed Studio XIAO ESP32-S3 and an Android companion application.
The project combines an ESP32-S3 camera device with an Android application to capture images, send them for AI analysis, and provide spoken responses.

Project Overview

Hei Seed consists of two main components:

-ESP32-S3 firmware: Handles camera operation, wake-word detection, Wi-Fi connectivity and communication with the Android application.
-Android application: Provides the user interface, receives images, communicates with the AI service and delivers spoken responses using text-to-speech.

The Android phone provides a Wi-Fi hotspot that connects the ESP32-S3 to the application.

Current Features:

Camera image capture using the XIAO ESP32-S3.
Wake-word detection.
Android companion application.
Image transfer from ESP32-S3 to Android.
AI-powered image analysis through OpenAI.
Norwegian text-to-speech output.
Heartbeat monitoring and connection status.
HMAC-SHA256 request authentication.
Device key management using Android Keystore.

Language Support:

Current limitation: Hei Seed is not yet trained or configured to reliably recognize Norwegian voice commands.

Improved Norwegian command recognition and voice interaction are planned for a future update.

Security Notes:

Communication between the Android application and ESP32-S3 currently uses HTTP over the Android phone's Wi-Fi hotspot.
Requests are protected by HMAC-SHA256 authentication and nonce validation to prevent unauthorized requests and replay attacks.

Known limitation: 

HTTP does not encrypt network traffic. Images and other transmitted data are not encrypted in transit.

The ESP32-S3 connects directly to the Android phone's hotspot. Users should protect their hotspot with a strong password and avoid allowing untrusted devices to connect.

HTTPS/TLS may be considered for a future version.

Hardware:

Seeed Studio XIAO ESP32-S3
Camera module
Android smartphone with Wi-Fi hotspot capability

Development Status:

Hei Seed is an ongoing development project. Features, hardware integration and language support are continuously being improved.

License:

This project is licensed under the MIT License.
License

This project is licensed under the MIT License.
