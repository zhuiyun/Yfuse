# Widget foreground artwork

This directory contains the reviewed, precomputed astronaut foreground used by the
widget editor's breakout preview. The image is read directly from assets; displaying
the preview requires no API key, network request, inference engine or model file.
The original/foreground pair, bounds and creation provenance are documented in
`PREVIEW-ASTRONAUT.md` and `preview-astronaut.json`.

Normal remote posters use the Poof pipeline when the user enables it and configures
a Poof API key. Successful transparent foregrounds are validated and cached locally;
failed requests retain the ordinary poster. The fixed preview is never substituted
for a remote poster.

ONNX Runtime and the U²-NetP model are no longer distributed with the application.
Their historical names in the preview provenance describe how that PNG was created,
not dependencies needed to display it. Independent experimental tools under `tools/`
have their own builds and do not contribute models or libraries to the main app.
