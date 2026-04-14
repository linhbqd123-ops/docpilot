# Key Management System - Quick Start & Deployment Guide

## Quick Start (Development)

### 1. Install Python Dependencies

```bash
cd packages/core-engine
pip install -r requirements.txt
```

This installs the new `cryptography>=42.0.0` library.

### 2. Start Backend Server

```bash
cd packages/core-engine
python main.py
```

Server runs on `http://localhost:8000`

### 3. UI Is Ready

Open the desktop app Settings panel → scroll down to "API Keys Management"

### 4. Try Adding a Key

1. Click "Edit" on any provider (e.g., OpenAI)
2. Paste a real or test API key (e.g., `sk-test123...`)
3. Click "Save"
4. Dialog closes, masked key appears (e.g., `sk-****...st123`)

### 5. Verify Encryption

Check the `.env` file:

```bash
cat packages/core-engine/.env
```

You should see:

```
OPENAI_API_KEY=encrypted:gAAAAABm...
```

Not:

```
OPENAI_API_KEY=sk-test123...
```

### 6. Verify Decryption

Restart the backend and check that keys are automatically decrypted and work normally.

---

## Production Deployment Checklist

### Backend Security

- [ ] **HTTPS/TLS Enabled**  
  Use environment variable or config to force HTTPS in production
  
- [ ] **CORS Restricted**  
  Update CORS origins to only include your actual app domain
  ```python
  allow_origins=[
      "https://yourdomain.com/app",
      "app://yourdomain",  # Tauri
  ]
  ```

- [ ] **Environment Isolation**  
  Separate `.env` files for dev/staging/production (use `.env.prod`)
  
- [ ] **Error Logging**  
  Disable verbose tech error messages in production logs
  ```python
  import logging
  logging.getLogger("FastAPI").setLevel(logging.WARNING)
  ```

- [ ] **Key File Permissions**  
  Set restrictive permissions on `.env` file:
  ```bash
  chmod 600 .env
  ```

- [ ] **Backup Security**  
  If backing up `.env`, keep backups encrypted and access-controlled

### Frontend Security

- [ ] **No Console Logging**  
  Remove all `console.log()` calls in prod build
  
- [ ] **Disable DevTools**  
  Disable F12/DevTools in production Tauri app:
  ```json
  // tauri.conf.json
  {
    "build": {
      "devPath": "...",
      "frontendDist": "..."
    },
    "app": {
      "windows": [{
        "devTools": false
      }]
    }
  }
  ```

- [ ] **HTTPS for API Calls**  
  All requests to backend use `https://` not `http://`

- [ ] **Content Security Policy**  
  Add strict CSP headers to prevent XSS

### Testing Before Release

- [ ] **Load Testing**  
  Test with 100+ keys to ensure no performance issues
  
- [ ] **Encryption Integrity**  
  Verify that modifying encrypted keys in `.env` properly fails decryption
  
- [ ] **Cross-Machine Portability**  
  Encrypted keys should NOT work if `.env` moved to another machine
  
- [ ] **Recovery Scenario**  
  Test recovery if user forgets to back up `.env` (keys unrecoverable by design)

---

## Environment Variables for Production

### Backend (.env.prod)

```env
# Local services
DOC_PROCESSOR_URL=http://localhost:8080

# Ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_DEFAULT_MODEL=llama3.2

# All API keys should be encrypted (added via Key Management UI)
# Do NOT add keys here manually!
```

### Frontend (.env or app config)

```env
VITE_API_BASE_URL=https://api.yourdomain.com
VITE_ENVIRONMENT=production
```

---

## Troubleshooting Production Issues

### Issue: "Failed to decrypt key" on new server

**Cause:** Each machine has a unique encryption key (based on MAC address)  
**Solution:** Keys are machine-specific. Cannot be migrated between servers. Re-add keys through UI.

### Issue: Keys disappeared after `.env` was restored

**Cause:** Restoring from backup on different machine (MAC address changed)  
**Solution:** This is by design (security feature). Re-add keys.

### Issue: User report: "I forgot my API key"

**Info Message:** "API keys are not stored in plaintext anywhere. For security, there's no recovery mechanism. You'll need to:"
1. Go to your provider's dashboard (OpenAI account, Groq account, etc.)
2. Generate a new API key
3. Return to DocPilot → Settings → Key Management
4. Paste the new key

---

## Encryption Details for DevOps

### Key Derivation Algorithm

```
PBKDF2-SHA256(
  password: machine_uuid.hex(),
  salt: b"docpilot_keys_v1",
  iterations: 100000,
  length: 32 bytes
)
```

### Encryption Algorithm

```
Fernet (AES-128-CBC) from cryptography library
- Automatically includes HMAC for authentication
- Timestamp for protection against timing attacks
- URL-safe base64 encoding
```

### Storage Format

```
encrypted:<base64(fernet_token)>
```

### Decryption Failure Behavior

- Silent failure with empty value (fallback to "")
- Warning logged: "⚠️  Warning: Failed to decrypt key, using empty value"
- Application continues (graceful degradation)

---

## Migration from Unencrypted to Encrypted Keys

### If you have existing unencrypted `.env` files:

```bash
# Backup original
cp packages/core-engine/.env packages/core-engine/.env.backup

# Delete or rename .env
mv packages/core-engine/.env packages/core-engine/.env.old

# Restart backend - it will create empty .env
python main.py

# Add keys through UI one by one
# They will be automatically encrypted
```

### If you want to migrate programmatically:

Use the `POST /api/keys/set` endpoint for each key:

```bash
curl -X POST http://localhost:8000/api/keys/set \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "openai",
    "key": "sk-...",
  }'
```

---

## Monitoring & Maintenance

### Health Checks

Monitor key management endpoints:

```bash
# Check if all providers have keys configured
curl https://api.yourdomain.com/api/keys/list

# Check specific provider
curl https://api.yourdomain.com/api/keys/check/openai
```

### Regular Maintenance

- Monthly: Review which keys/providers are actually being used
- Quarterly: Rotate keys for unused providers (security best practice)
- Yearly: Generate new API keys from providers (if they recommend rotation)

### Logs to Monitor

```
ERROR: Failed to decrypt key  # Indicates corruption/tampering
WARNING: Failed to save key    # File permission issues
INFO: Key updated              # Normal operation (optional)
```

---

## FAQ

**Q: Can users export their keys?**  
A: No, by design. Users can only export new keys FROM their providers.

**Q: What if the `.env` file is lost?**  
A: Keys are unrecoverable (this is desired for security). Users must re-add them.

**Q: Can I run the app on multiple machines with same keys?**  
A: No, encryption is machine-specific. Each machine must have keys added independently.

**Q: Is encryption required?**  
A: Yes for production. The backward-compatible design allows existing unencrypted keys to work, but all NEW keys are encrypted.

**Q: What if someone has shell access to the server?**  
A: At that point, security is compromised at OS level. Encryption provides protection against less severe threats (file sharing, backups, etc.)

---

## Support & Reporting Issues

### For Users

If key management isn't working:
1. Check backend is running: `GET /api/health` should return `{status: "ok"}`
2. Check browser console (F12) for error messages
3. Check backend logs for encryption errors
4. Try restarting the app
5. Contact support with screenshots of the error

### For Developers

Report issues with:
1. Exact steps to reproduce
2. Backend logs (`tail -f backend.log`)
3. Browser console errors (F12)
4. `.env` file structure (with sensitive data removed)
5. Machine info (Windows/Mac/Linux, architecture)
