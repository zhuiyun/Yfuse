// Render the actual Kotlin vector paths for desktop visual review, without a device.
const fs = require('fs');
const sharp = require('C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp');
const source = fs.readFileSync('composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/AppIcons.kt', 'utf8');
function glyph(name) {
  const block = source.split(`    val ${name} =`)[1].split('.build()')[0];
  let paths = '', d = '', width = 1.8;
  function flush() {
    if (d) paths += `<path d="${d}" fill="none" stroke="white" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"/>`;
    d = '';
  }
  for (let line of block.split('\n')) {
    line = line.trim();
    if (line.includes('andPath')) { flush(); width = Number(line.match(/width = ([\d.]+)f/)?.[1] || 1.8); }
    if (line.includes('andDots')) {
      flush();
      for (const m of line.matchAll(/([\d.]+)f to ([\d.]+)f/g)) paths += `<circle cx="${m[1]}" cy="${m[2]}" r="0.85" fill="white"/>`;
    }
    const m = line.match(/^(moveTo|lineTo|horizontalLineTo|verticalLineTo|curveTo|close|roundRect|circle)\(([^)]*)\)/);
    if (!m) continue;
    const a = m[2].split(',').map(v => Number(v.trim().replace(/f$/, '')));
    if (m[1] === 'roundRect') {
      flush(); const [x,y,w,h,r] = a;
      paths += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="none" stroke="white" stroke-width="${width}"/>`;
    } else if (m[1] === 'circle') {
      flush(); paths += `<circle cx="${a[0]}" cy="${a[1]}" r="${a[2]}" fill="none" stroke="white" stroke-width="${width}"/>`;
    } else {
      d += ({moveTo:'M',lineTo:'L',horizontalLineTo:'H',verticalLineTo:'V',curveTo:'C',close:'Z'})[m[1]];
      if (m[1] !== 'close') d += a.join(' ') + ' ';
    }
  }
  flush(); return paths;
}
let body = '<rect width="650" height="170" fill="#1e242a"/>';
['Subtitle','AudioTrack','EpisodeList','Danmaku','Danmaku'].forEach((name, i) => {
  const x = 64 + i * 130;
  body += `<circle cx="${x}" cy="64" r="39" fill="${i === 4 ? '#ffffff1f' : 'none'}" stroke="#ffffff9e" stroke-width="3"/>`;
  body += `<g transform="translate(${x-18} 46) scale(1.5)">${glyph(name)}</g>`;
  body += `<text x="${x}" y="132" text-anchor="middle" fill="#d4d8df" font-family="Segoe UI" font-size="13">${['Subtitle','Audio','Episodes','Danmaku','Danmaku on'][i]}</text>`;
});
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="650" height="170">${body}</svg>`;
fs.writeFileSync('audit/runtime-optimization-20260910/icon-preview.svg', svg);
sharp(Buffer.from(svg)).png().toFile('audit/runtime-optimization-20260910/icon-preview.png');
