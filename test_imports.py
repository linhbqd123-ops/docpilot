import sys
import os

print("Current directory:", os.getcwd())
print("Python path includes llm-layer:", any('llm-layer' in p for p in sys.path))

print("\nTesting imports...")
try:
    from app.main import app
    print("✅ App import successful")
except Exception as e:
    print("❌ App import failed:", e)
    import traceback
    traceback.print_exc()