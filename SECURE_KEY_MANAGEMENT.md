# Secure Key Management System - Implementation Guide

## Overview

This document describes the secure API key management system implemented for DocPilot Desktop, a fully-offline desktop application. The system provides end-to-end encrypted key storage with a clean, user-friendly frontend interface.

---

## Architecture

### Design Principles

1. **No Key Storage on Frontend**
   - Keys are never persisted in browser localStorage, session storage, or IndexedDB
   - Frontend only displays masked keys (first 4 + last 4 characters)
   - Frontend detects key changes and syncs to backend

2. **Encryption at Rest**
   - Keys are encrypted using Fernet (AES-128 with HMAC) before being saved to `.env`
   - Master key is derived from machine-specific hardware ID (MAC address)
   - Master key is re-derived on-demand, never stored on disk
   - Backward compatible with unencrypted keys (for migration)

3. **Offline Safety**
   - Since the app runs fully offline on user's machine, encryption provides protection against:
     - Accidental exposure if `.env` file is shared or backed up
     - Tampering with configuration files
     - Reverse engineering of plaintext keys

4. **User Control**
   - Users decide what keys to configure
   - Easy add/update/delete operations
   - Clear security messaging

---

## Backend Architecture

### Crypto System (`crypto.py`)

```python
# Key Derivation
Machine ID (MAC) + Fixed Salt (PBKDF2) → Master Key (256-bit)

# Storage Format
"encrypted:<base64(Fernet(plaintext_key))>"
```

**Key Functions:**

- `encrypt_key(plaintext_key)` → Returns encrypted key in storage format
- `decrypt_key(encrypted_key)` → Returns plaintext key (or None on failure)
- `mask_key(key)` → Returns masked display format (e.g., "sk-****...****")

**Security Notes:**
- PBKDF2 with 100,000 iterations (CPU-intensive to prevent brute force)
- Fernet includes timestamp and HMAC (prevents tampering)
- Machine ID provides per-machine encryption (keys aren't portable across machines)

### API Endpoints (`api/keys.py`)

#### `POST /api/keys/set`
Set or update an API key for a provider

**Request:**
```json
{
  "provider": "openai",
  "key": "sk-...",
  "endpoint": "https://..."  // Optional, for providers like Azure
}
```

**Response:**
```json
{
  "provider": "openai",
  "has_key": true,
  "masked_key": "sk-****...****"
}
```

**Action:** Backend encrypts key and saves to `.env` file

---

#### `GET /api/keys/check/{provider}`
Check if a provider has a configured key

**Request:** `GET /api/keys/check/openai`

**Response:**
```json
{
  "provider": "openai",
  "has_key": true,
  "masked_key": "sk-****...****"
}
```

---

#### `GET /api/keys/list`
List all providers and their key status

**Request:** `GET /api/keys/list`

**Response:**
```json
{
  "providers": [
    {
      "provider": "openai",
      "has_key": true,
      "masked_key": "sk-****...****"
    },
    {
      "provider": "groq",
      "has_key": false
    }
  ]
}
```

---

#### `DELETE /api/keys/delete/{provider}`
Remove a key for a provider

**Request:** `DELETE /api/keys/delete/openai`

**Response:**
```json
{
  "success": true,
  "message": "Key for openai deleted"
}
```

---

### Automatic Decryption on Startup (`config.py`)

The `Settings` class uses Pydantic validators to automatically decrypt encrypted keys when loading from `.env`:

```python
@field_validator("openai_api_key", ..., mode="before")
@classmethod
def decrypt_api_keys(cls, v: str) -> str:
    """Automatically decrypt encrypted API keys on load."""
    if v and v.startswith("encrypted:"):
        decrypted = decrypt_key(v)
        return decrypted or ""
    return v
```

**Flow:**
1. `.env` contains encrypted keys like `OPENAI_API_KEY=encrypted:...`
2. Settings loader detects `encrypted:` prefix
3. Uses `decrypt_key()` to get plaintext
4. Returns plaintext to the application
5. Application uses plaintext normally

---

## Frontend Architecture

### Components

#### 1. `MaskedKeyInput.tsx`
Reusable input component with show/hide functionality

**Features:**
- Password-type input (hidden by default)
- Show/hide toggle button
- Clear/reset option
- Error state support
- Displays current masked key

```tsx
<MaskedKeyInput
  label="API Key"
  value={key}
  onChange={setKey}
  maskedDisplay={currentMaskedKey}
  onClear={() => setKey("")}
  error={error}
/>
```

---

#### 2. `KeyEditorDialog.tsx`
Modal dialog for adding/updating keys

**Features:**
- Edit or create mode (based on whether key exists)
- Optional endpoint input field (for Azure, Z.AI, Ollama)
- Delete button (if key exists)
- Loading state with spinner
- Error messages
- Confirms deletion before removing

---

#### 3. `KeyManager.tsx`
Main component displaying all providers and their status

**Features:**
- Lists all supported providers (OpenAI, Groq, Anthropic, Azure, OpenRouter, Together, Z.AI, Ollama)
- Shows masked key for each provider
- Edit button for each provider (changes color based on key status)
- Loading state
- Error handling with retry
- Security messaging

**Props:**
```tsx
interface KeyManagerProps {
  baseUrl: string;  // Backend base URL from app settings
}
```

---

#### 4. HTTP Client (`lib/api.ts`)

Simple wrapper around fetch for API calls:

```tsx
class HTTPClient {
  async get<T>(path: string): Promise<{ data: T }>
  async post<T>(path: string, body: unknown): Promise<{ data: T }>
  async delete<T>(path: string): Promise<{ data: T }>
}

// Used by KeyManager
const apiClient = new HTTPClient(baseUrl);
```

---

### Integration with Settings Panel

The `KeyManager` is embedded in `SettingsPanel.tsx`:

```tsx
<div className="panel-card space-y-4 p-4">
  <KeyManager baseUrl={state.settings.apiBaseUrl} />
</div>
```

Keys are managed completely independently from app state/reducer (no Redux/Context involvement needed).

---

## User Experience Flow

### Adding a Key

1. User opens Settings panel
2. Finds the provider in Key Management section
3. Clicks the "Edit" button (blue if no key, gray if key exists)
4. Dialog opens: "Add [Provider] Key"
5. User pastes API key (shown as password dots)
6. (Optional) User enters custom endpoint
7. User clicks "Save"
8. **Backend:** Key is encrypted and saved to `.env`
9. **Frontend:** Dialog closes, masked key is displayed
10. **Backend:** On next startup, key is automatically decrypted

### Updating a Key

1. User clicks "Edit" for provider with existing key
2. Dialog opens: "Update [Provider] Key"
3. Current masked key is shown
4. User pastes new key
5. User clicks "Save"
6. Old key is replaced (encrypted)

### Deleting a Key

1. User clicks "Edit" for provider
2. Dialog shows "Delete" button (red)
3. User clicks "Delete"
4. Confirmation dialog appears
5. On confirm: Key is removed from `.env`
6. Frontend shows "No key configured" again

---

## Security Considerations

### What's Encrypted

✅ All API keys in `.env` file  
✅ Endpoint URLs for custom providers  
✅ Any sensitive configuration

### What's Not Encrypted

⚠️ Application logs (should use loglevel=WARNING in production)  
⚠️ Network traffic (protected by HTTPS/SSL when used with remote services)  
⚠️ Application memory (while decrypted for use)

### Additional Recommendations

1. **For Production Desktop Distribution:**
   - Code-sign the desktop application
   - Use HTTPS for any remote API calls
   - Disable console logging in production builds
   - Consider adding a user password/PIN for additional security

2. **User Best Practices:**
   - Never share `.env` files
   - Keep desktop app updated
   - Don't enable "Developer Tools" in production
   - Back up `.env` file securely if needed

---

## Implementation Details

### File Structure

```
packages/core-engine/
├── crypto.py                 # Encryption/Decryption utilities
├── config.py                 # Updated with validators
├── requirements.txt          # Added: cryptography>=42.0.0
└── api/
    ├── keys.py              # Key management endpoints
    └── ...

packages/desktop/src/
├── components/ui/
│   ├── MaskedKeyInput.tsx   # Reusable input component
│   ├── KeyEditorDialog.tsx  # Modal for editing keys
│   └── KeyManager.tsx       # Main key management component
├── components/sidebar/
│   └── SettingsPanel.tsx    # Updated with KeyManager
└── lib/
    └── api.ts               # Updated with HTTPClient
```

### Dependencies Added

Backend:
- `cryptography>=42.0.0` - Fernet encryption

Frontend:
- No new dependencies (uses existing Lucide icons)

---

## Testing Checklist

### Backend Testing

- [ ] `POST /api/keys/set` with valid key
- [ ] `POST /api/keys/set` with invalid provider  
- [ ] `GET /api/keys/check/{provider}` returns masked key
- [ ] `GET /api/keys/list` returns all providers
- [ ] `DELETE /api/keys/delete/{provider}` removes key
- [ ] `.env` file contains encrypted keys (starts with "encrypted:")
- [ ] Keys are decrypted correctly on Settings reload

### Frontend Testing

- [ ] KeyManager loads provider list on mount
- [ ] Edit button opens dialog for each provider
- [ ] Can enter and save new key
- [ ] Masked key displays correctly (first 4 + last 4)
- [ ] Clear button works in input
- [ ] Show/hide toggle works
- [ ] Delete button appears only when key exists
- [ ] Delete requires confirmation
- [ ] Error messages display clearly
- [ ] Loading states show spinner
- [ ] Can update existing key
- [ ] Multiple providers can be configured

### Integration Testing

- [ ] Save key → Appears in masked display
- [ ] Close app → Exit key manager
- [ ] Restart app → Key still appears (verified from `.env` decryption)
- [ ] Change key → Backend saves encrypted version
- [ ] Switch providers → Each has independent key storage
- [ ] Network error → Shows error message and retry button

---

## Troubleshooting

### Issue: Keys not persisting

**Solution:** Check `.env` file location (should be at project root). Verify file permissions.

### Issue: "Failed to decrypt key" warning

**Cause:** Machine ID changed (very rare - only if hardware changed)  
**Solution:** Re-enter keys through UI

### Issue: Endpoint field not showing for some providers

**Expected behavior:** Only Azure, Z.AI, and Ollama show endpoint field  
**To add for another provider:** Update `PROVIDERS` array in `KeyManager.tsx`

### Issue: Frontend doesn't show key after saving

**Cause:** Backend error (check server logs) or network issue  
**Solution:** Check browser console for errors, verify backend is running

---

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                    Desktop App (Frontend)                     │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ Settings Panel                                          │ │
│  │  └─ KeyManager Component                                │ │
│  │      ├─ Load provider status (GET /api/keys/list)      │ │
│  │      ├─ Display providers with masked keys             │ │
│  │      └─ Open KeyEditorDialog on edit                   │ │
│  │          ├─ Save key (POST /api/keys/set)              │ │
│  │          └─ Delete key (DELETE /api/keys/delete)       │ │
│  └─────────────────────────────────────────────────────────┘ │
└────────────────┬─────────────────────────────────────────────┘
                 │ HTTP (HTTPS recommended)
                 │
┌────────────────▼─────────────────────────────────────────────┐
│                    Backend (Python)                           │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ Key Management API (api/keys.py)                        │ │
│  │  ├─ POST /api/keys/set                                  │ │
│  │  ├─ GET /api/keys/check/{provider}                      │ │
│  │  ├─ GET /api/keys/list                                  │ │
│  │  └─ DELETE /api/keys/delete/{provider}                  │ │
│  └──────────────────┬──────────────────────────────────────┘ │
│                     │                                         │
│  ┌──────────────────▼──────────────────────────────────────┐ │
│  │ Crypto Utilities (crypto.py)                            │ │
│  │  ├─ encrypt_key(plaintext) → "encrypted:<base64>"      │ │
│  │  ├─ decrypt_key(encrypted) → plaintext                  │ │
│  │  └─ derive_master_key() → Per-machine key              │ │
│  └──────────────────┬──────────────────────────────────────┘ │
│                     │                                         │
│  ┌──────────────────▼──────────────────────────────────────┐ │
│  │ .env File (Local Storage)                               │ │
│  │  ├─ OPENAI_API_KEY=encrypted:...                        │ │
│  │  ├─ GROQ_API_KEY=encrypted:...                          │ │
│  │  └─ ... (all encrypted)                                 │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

---

## Future Enhancements

1. **Master Password Protection**  
   Add optional password to unlock key management section

2. **Key Rotation**  
   Scheduled automatic key renewal for high-security environments

3. **Key Backup/Export**
   Allow users to export encrypted backup of keys

4. **Audit Logging**
   Track when keys were added/modified/deleted

5. **Multi-Device Sync**  
   For cloud-based deployments (requires encrypted transport)

---

## References

- [Cryptography Library Docs](https://cryptography.io/en/latest/)
- [Fernet (Symmetric Encryption)](https://cryptography.io/en/latest/fernet/)
- [PBKDF2 Key Derivation](https://en.wikipedia.org/wiki/PBKDF2)
- [API Key Security Best Practices](https://learn.microsoft.com/en-us/rest/api/azure/#api-keys)
