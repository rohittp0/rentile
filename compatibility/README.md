# Compatibility manifests

`rentile-v1.json` pins the exact layer-policy outcomes for the initial 34-style
corpus without committing style documents, resource URLs, credentials, or provider
assets.

Regenerate it only from an authorized local corpus:

```shell
python3 tools/generate_compatibility_manifest.py \
  /path/to/styles \
  compatibility/rentile-v1.json
```

The generator rejects aggregate count drift and refuses to write output containing
a URL or credential-shaped material. A manifest change is a renderer compatibility
change and must be reviewed alongside the compiler and its golden tests.

Validate the committed artifact without access to the private corpus:

```shell
python3 tools/check_compatibility_manifest.py compatibility/rentile-v1.json
```
