# Debugging Patch Apply Failures

## Error: "Patch target disappeared during apply"

### What This Means

When you see this error:
```
java.lang.IllegalStateException: Patch target disappeared during apply at operation X/Y: 
block=c068ecee-a09f-44ae-9bba-2356df733062
```

It means:
1. ✅ Revision was **successfully staged** (dry-run passed)
2. ✅ Started applying the patch operations  
3. ❌ **Failed at operation X** trying to patch a block that no longer exists

### Why Blocks Disappear

The patch contains 5 operations (or more), applied sequentially:

1. **Operation 1**: Patches block A → SUCCESS ✅
2. **Operation 2**: Patches block B → SUCCESS ✅
3. **Operation 3**: Patches block C → SUCCESS ✅
4. **Operation 4**: Tries to patch block D → **BLOCK D IS GONE** ❌

Possible reasons block D disappeared:

| Reason | How It Happens | Prevention |
|--------|---|---|
| **Block was deleted** | Operation 1-3 deleted the block that operation 4 needs | Ensure operations don't delete blocks referenced later |
| **Document changed externally** | Another user/session modified the document between staging and apply | Lock document during staged period |
| **Block ID changed** | Structural changes altered block IDs (rare) | Rebuild indices properly |
| **Index mismatch** | The index wasn't rebuilt correctly after an operation | Check anchor resolution |

### How to Diagnose

#### 1. Check the Error Details

The enhanced error message now includes:

```
Patch apply failed: Patch target disappeared during apply at operation 3/5:
block=c068ecee-a09f-44ae-9bba-2356df733062
Operation type: REPLACE_TEXT_RANGE
Available blocks: [p_001, p_002, p_003, table_001, ...]
Suggestion: The document may have been modified since the patch was staged. Try re-staging the revision.
```

**Key information:**
- **Operation 3/5** - Failed on 3rd of 5 operations
- **REPLACE_TEXT_RANGE** - The operation type
- **Available blocks** - What blocks exist at the time of failure
- The missing block **is not in the available list**

#### 2. View Doc-MCP Error Logs

```bash
# View recent error from doc-mcp
curl http://localhost:8000/api/debug/logs/doc-mcp-error.jsonl?limit=10 | jq .

# Look for errors with traceId matching the failed revision
grep "traceId.*<your-trace-id>" logs/*.jsonl | jq .
```

The logs will show:
- Exactly which operation failed
- What the target block was
- What blocks were available
- Full stack trace

#### 3. Check Core-Engine Error Logs

```bash
# View core-engine errors
curl http://localhost:8000/api/debug/logs/core-engine-error.jsonl?limit=10 | jq .

# Look for agent.error.doc_mcp events
grep "agent.error.doc_mcp" logs/core-engine-error.jsonl | jq .
```

#### 4. Trace the Revision Lifecycle

```bash
# Find all events for a specific revision
grep "revisionId.*rev_abc123" logs/*.jsonl | jq 'select(.event | contains("revision"))' | sort_by(.timestamp)
```

This shows:
- When revision was staged
- When staging succeeded
- When apply was attempted
- At what operation it failed
- What blocks existed before/after each operation

### Solutions

#### Option 1: Re-stage the Revision

The **simplest solution** - the revision should still be valid:

```bash
# Frontend: Click "Discard" then create a new revision with the same changes
# OR manually stage it again
```

Re-staging will:
- Re-validate all operations against current document state
- Update block references to match current structure
- Detect any new conflicts

#### Option 2: Identify the Conflict Manually

If re-staging also fails:

1. Check what operations are in the revision
2. Verify each target block still exists:
   ```bash
   curl http://localhost:8000/api/sessions/<sessionId>/html | grep "data-doc-node-id=\"<blockId>\""
   ```
3. Check if document structure changed
4. Look for newer revisions that might have deleted the block

#### Option 3: Roll Back to Base

If document is corrupted, roll back to the base revision:

```bash
curl -X POST http://localhost:8000/api/agent/revisions/<revisionId>/rollback
```

This restores the document to the state when the patch was staged.

### Prevention

#### 1. Validate Operations Don't Conflict

When staging multiple operations, check they don't:
- Delete blocks referenced by later operations
- Modify structure in ways that invalidate later targets
- Operate on overlapping ranges in the same block

#### 2. Lock Document During Staged Period

If staging takes time before applying:
```
Document Locked [Staged Revision ID]
- Block <id>: Changes pending
- Don't make other edits to these blocks!
```

#### 3. Shorter Staging Window

Apply revisions quickly after staging to minimize chance of concurrent modifications.

#### 4. Check Dry-Run Results

The dry-run already validates operations will work:
```json
{
  "valid": true,
  "errors": [],
  "warnings": [],
  "affectedBlockIds": [
    "p_001", "p_002", "p_003"
  ]
}
```

If dry-run passes, apply should also pass (unless document changed).

## Debugging in Code

### Check PatchEngine.apply() Log Output

When apply fails, check these logs:

```
[ERROR] PatchEngine - Patch apply failed: Patch target disappeared during apply at operation X/Y...
[ERROR] PatchEngine - Patch ID: patch_abc, Session ID: session_123, Revision ID: rev_def
[ERROR] PatchEngine - Operation type: REPLACE_TEXT_RANGE
[ERROR] PatchEngine - Available blocks: [p_001, p_002, ...]
```

### Check RevisionService.apply() Log Output

```
[ERROR] RevisionService - Failed to apply patch during revision apply: ...
[ERROR] RevisionService - Revision rejected: rev_abc123
```

### Enable Debug Logging

To see more detailed logs:

```properties
# In logback-spring.xml
<logger name="io.docpilot.mcp.engine.patch" level="DEBUG"/>
<logger name="io.docpilot.mcp.engine.revision" level="DEBUG"/>
```

This will log:
- Before each operation: target and current state
- After each operation: index rebuild result
- Block resolution attempts

## The User's Specific Case

The error message shows:

```
Block disappeared: c068ecee-a09f-44ae-9bba-2356df733062
Capitalization fix for "LINH"
5 operations total (names in document)
```

**Possible causes:**
1. The name blocks are spread across the document
2. One of the first operations modified the structure
3. The 4th or 5th operation references a block that got modified

**Debug steps:**
1. ✅ Re-staging should fix it (blocks re-indexed)
2. ✅ Check if document was edited between staging and apply
3. ✅ Verify all 5 operations target different blocks
4. ✅ Look for any block deletions in earlier operations

## Related Documentation

- [LOGGING_SYSTEM.md](LOGGING_SYSTEM.md) - How to view logs
- [PatchEngine](packages/doc-mcp/src/main/java/io/docpilot/mcp/engine/patch/PatchEngine.java) - Source code
- [RevisionService](packages/doc-mcp/src/main/java/io/docpilot/mcp/engine/revision/RevisionService.java) - Service layer
