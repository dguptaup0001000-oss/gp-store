@echo off
REM Runs the Flutter web app locally against your local backend, with both
REM required flags set correctly every time - no more forgetting one of them.
REM
REM --web-port=3000   matches the backend's CORS default (cors.allowed-origins
REM                   in application.properties) - any other port gets
REM                   silently blocked by the browser.
REM --dart-define=API_BASE_URL=...  points the app at your LOCAL backend
REM                   (localhost:8081/v1), overriding the built-in fallback
REM                   (10.0.2.2:8081/v1) which only works from an Android
REM                   emulator, never from a real desktop Chrome window.
REM
REM Make sure your backend (mvnw.cmd spring-boot:run) is already running in
REM another window before running this - this script only starts the
REM frontend, not the backend.

flutter run -d chrome --web-port=3000 --dart-define=API_BASE_URL=http://localhost:8081/v1
