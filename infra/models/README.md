Local embedding models

Place ONNX + tokenizer files under this folder. The vectorizer-service mounts this directory to /models inside the container.

Expected layout for MiniLM:

- all-MiniLM-L6-v2/
  - model.onnx
  - tokenizer.json
  - config.json

Paths configured in vectorizer-service:
- model-uri: file:/models/all-MiniLM-L6-v2/model.onnx
- tokenizer-uri: file:/models/all-MiniLM-L6-v2/tokenizer.json

Tip: run scripts/download_minilm_onnx.sh to fetch the files.
