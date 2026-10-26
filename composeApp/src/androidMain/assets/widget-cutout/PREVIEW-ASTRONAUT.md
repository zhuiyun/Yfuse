# Original breakout preview artwork

`widget-preview/breakout-astronaut.png` is original artwork generated with the built-in
image generation tool for this project's widget preview, on 2026-10-25. It contains no
third-party movie branding, title, logo, or text. The 1024 × 1536 generated source was
bounded to 512 × 768 for the app asset.

`preview-astronaut.png` is its real U2NetP foreground output, not a generated transparent
cutout. The mask was not manually painted, repaired, thresholded, or adjusted to pass
the gate. It uses the bundled model, RGB/max-pixel normalization, ImageNet mean/std,
320 × 320 bilinear input, and normalized output alpha resized to 512 × 768.
The image pair shares exact dimensions and RGB coordinates.

The output passed the actual common Kotlin `assessWidgetCutoutMask` implementation.
The gray-background visual review retained the helmet, both hands, both legs and both
boots with no large background islands. This reviewed, fixed example is separate from
the runtime processing of arbitrary user posters. Its bounds and metrics are recorded
in `preview-astronaut.json`; recorded timing is a desktop CPU sample, not a phone result.

## Generation prompt

Use case: stylized-concept. Asset type: original cinematic illustration artwork for a
mobile streaming app's 3D breakout poster preview. Create one vertical 2:3 image with one
single astronaut, full body fully visible, floating gently upright with a slight dynamic
lean. The astronaut is the ONLY distinct object: white and silver spacesuit, dark gold
visor, realistic elegant fabric and hardware, dramatic directional light, cinematic
painterly realism. Both arms, gloves, legs and boots clearly visible and connected,
limbs apart enough for a readable silhouette, no long thin cords or free-floating
accessories. Composition: the astronaut centered horizontally, occupying about 65% of
canvas height and 45% of width. Top of helmet at 17% height, bottom of boots at 82% height.
Leave generous clear empty space around the entire silhouette. Background: a quiet pale
blue cosmic haze, subtle smooth blue-gray gradient and very sparse faint pinprick stars,
much darker at upper corners; NO planets, moons, rocks, rings, landscape, horizon,
geometric framing, circles, visible sun or other competing object. Strong clean separation
between subject silhouette and backdrop. Beautiful premium cinema key art, richly
detailed subject, restrained and uncluttered background. Absolutely no text, typography,
labels, logos, brand marks, flags, watermark or border. Opaque full background.
