---
name: verification-and-tooling
description: Instructions for running static analysis, typechecking, dead-code detection, architecture validation, unit tests, and process cleanup in Morgana ORPG.
---

# Verification & Tooling Skill

## Quality Gate Commands
Run the unified check before finishing any feature or refactor:
```powershell
.\gradlew.bat check
```

## Individual Diagnostic Tools
- **Tests**: `.\gradlew.bat test`
- **Server**: `.\gradlew.bat :server-app:run`
- **Client**: `.\gradlew.bat :client-desktop:run`


## Process & Port Cleanup
If a background dev server or test worker hangs on a port:
```powershell
lsof -i :3000
fuser -k 3000/tcp
pkill -ef "src/server"
```
