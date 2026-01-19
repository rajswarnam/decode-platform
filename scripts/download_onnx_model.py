#!/usr/bin/env python3
"""
Download and convert all-MiniLM-L6-v2 to ONNX format.
Requires: pip install optimum[onnxruntime] transformers
"""

import os
import sys

try:
    from optimum.onnxruntime import ORTModelForFeatureExtraction
    from transformers import AutoTokenizer
except ImportError:
    print("Installing required packages...")
    import subprocess
    subprocess.check_call([
        sys.executable, "-m", "pip", "install", 
        "optimum[onnxruntime]", "transformers", "--quiet"
    ])
    from optimum.onnxruntime import ORTModelForFeatureExtraction
    from transformers import AutoTokenizer

def main():
    # Resolve repo root (script location is repo/scripts)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    root_dir = os.path.dirname(script_dir)
    model_dir = os.path.join(root_dir, "infra", "models")
    
    os.makedirs(model_dir, exist_ok=True)
    
    print(f"Downloading all-MiniLM-L6-v2 model to: {model_dir}")
    print("This may take a few minutes...")
    
    try:
        # Download and convert to ONNX
        model = ORTModelForFeatureExtraction.from_pretrained(
            "sentence-transformers/all-MiniLM-L6-v2",
            export=True
        )
        tokenizer = AutoTokenizer.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")
        
        # Save to model directory
        model.save_pretrained(model_dir)
        tokenizer.save_pretrained(model_dir)
        
        # Verify files
        model_file = os.path.join(model_dir, "model.onnx")
        tokenizer_file = os.path.join(model_dir, "tokenizer.json")
        
        if os.path.exists(model_file) and os.path.getsize(model_file) > 1000000:  # > 1MB
            print(f"✓ Model downloaded successfully!")
            print(f"  Model: {model_file} ({os.path.getsize(model_file) / 1024 / 1024:.1f} MB)")
            print(f"  Tokenizer: {tokenizer_file} ({os.path.getsize(tokenizer_file) / 1024:.1f} KB)")
        else:
            print("✗ Model file seems invalid (too small)")
            sys.exit(1)
            
    except Exception as e:
        print(f"✗ Error: {e}")
        print("\nAlternative: Let Spring AI auto-download the model on first startup.")
        print("Just remove the model-uri from application.yaml and restart.")
        sys.exit(1)

if __name__ == "__main__":
    main()
