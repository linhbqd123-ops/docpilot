# Summary: Logging & Patch Apply Error Fixes

## Changes Made

### 1. **Fixed Doc-MCP Logging Configuration**
**File**: `packages/doc-mcp/src/main/resources/logback-spring.xml`

**Problem**: Relative path `../../../logs/` didn't resolve correctly when JAR runs from different locations.

**Solution**:
- Use environment variable `LOG_DIR` to specify absolute path
- Default fallback: `${user.home}/.docpilot/logs`
- Format: `LOG_DIR=/e:/docpilot/logs` (Windows) or `LOG_DIR=/var/log/docpilot/logs` (Linux)

**Result**: ✅ doc-mcp logs now write to persistent, absolute path

### 2. **Enhanced PatchEngine Error Handling**
**File**: `packages/doc-mcp/src/main/java/io/docpilot/mcp/engine/patch/PatchEngine.java`

**Changes**:
- Added detailed error context when patch apply fails
- Shows operation number (e.g., "3/5" for 3rd of 5 operations)
- Lists available blocks at time of failure
- Includes operation type for debugging
- Better suggestion messages

**Before**:
```
java.lang.IllegalStateException: Patch target disappeared during apply: block=c068ecee-...
```

**After**:
```
Patch target disappeared during apply at operation 3/5: block=c068ecee-a09f-44ae-9bba-2356df733062
Operation type: REPLACE_TEXT_RANGE
Available blocks: [p_001, p_002, p_003, ...]
Suggestion: The document may have been modified since the patch was staged. Try re-staging the revision.
```

**Result**: ✅ Much easier to debug patch apply failures

### 3. **Enhanced RevisionService Error Handling**
**File**: `packages/doc-mcp/src/main/java/io/docpilot/mcp/engine/revision/RevisionService.java`

**Changes**:
- Wraps PatchEngine.apply() in try-catch
- Logs detailed error information
- Marks revision as REJECTED on apply failure
- Provides user-friendly error messages

**Result**: ✅ Failures don't corrupt system state, clear error messages

## Files Changed

| File | Change | Impact |
|------|--------|--------|
| `packages/doc-mcp/src/main/resources/logback-spring.xml` | Config path resolution | ✅ Logs persist to disk |
| `packages/doc-mcp/src/main/java/.../PatchEngine.java` | Enhanced error context | ✅ Better debugging |
| `packages/doc-mcp/src/main/java/.../RevisionService.java` | Error handling wrapper | ✅ Safe failure handling |

## What to Do Now

### 1. Rebuild Doc-MCP

```bash
cd packages/doc-mcp

# Windows
mvn clean package

# OR use the build script
build.bat
```

### 2. Set Log Directory (Important!)

Before running:

```bash
# Windows PowerShell
$env:LOG_DIR = "C:\docpilot\logs"
java -jar target/doc-mcp-*.jar

# OR Windows CMD
set LOG_DIR=C:\docpilot\logs
java -jar target/doc-mcp-*.jar

# Linux/Mac
export LOG_DIR=/var/log/docpilot/logs
java -jar target/doc-mcp-*.jar
```

Or add to `.env`:
```
LOG_DIR=C:\Users\Admin\.docpilot\logs
```

### 3. Verify Logs Are Being Created

```bash
# Check if logs folder exists and has files
dir C:\docpilot\logs    # Windows
ls /var/log/docpilot/logs    # Linux

# Should show:
# doc-mcp-info.log
# doc-mcp-error.log
```

### 4. Test by Attempting Apply

1. Stage a revision (e.g., capitalization fix)
2. Try to apply it
3. If it fails, check logs:
   ```bash
   curl http://localhost:8000/api/debug/logs/doc-mcp-error.jsonl?limit=5 | jq .
   ```

## Debugging the Patch Disappearance Issue

Your specific error:
```
Patch target disappeared during apply: block=c068ecee-a09f-44ae-9bba-2356df733062
```

**Root cause**: One of the 5 capitalization operations references a block that no longer exists.

**Solution 1 (Recommended)**: Re-stage the revision
- This will refresh block references against current document
- Should succeed if document hasn't been corrupted

**Solution 2**: Check logs with new enhanced messages
```bash
curl http://localhost:8000/api/debug/logs/doc-mcp-error.jsonl | jq '.[0]'
```

Look for:
- Which operation (1-5) failed
- What operation type
- What blocks were available

**Solution 3**: Manual fix
- Check if the document structure changed
- Verify no other operations deleted the block
- Roll back if needed with `/api/agent/revisions/<id>/rollback`

## Documentation Files

Two new guides created:

1. **[LOGGING_SYSTEM.md](LOGGING_SYSTEM.md)** - Overall logging architecture
2. **[PATCH_APPLY_DEBUG_GUIDE.md](PATCH_APPLY_DEBUG_GUIDE.md)** - Specific debugging for patch apply failures

## Next Steps

1. ✅ Rebuild doc-mcp with `mvn clean package`
2. ✅ Set `LOG_DIR` environment variable before running
3. ✅ Restart doc-mcp service
4. ✅ Verify logs are being created
5. ✅ Try apply_revision again - should now see detailed error in logs
6. ✅ Use [PATCH_APPLY_DEBUG_GUIDE.md](PATCH_APPLY_DEBUG_GUIDE.md) to diagnose

## Verification Checklist

- [ ] Doc-MCP rebuilt successfully
- [ ] LOG_DIR environment variable set
- [ ] doc-mcp-*.log files created in logs folder
- [ ] Logs contain both INFO and ERROR entries
- [ ] Apply attempt shows detailed error message in logs
- [ ] Can access logs via `/api/debug/logs` endpoints
