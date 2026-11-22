// Turns the raw recording from trailer.mjs into build/trailer/halcyon-reach-trailer.mp4: trims the login, captions each scene, adds title and end cards.
// Usage (from smoke-test/): node trailer-edit.mjs      Needs ffmpeg on PATH.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs'; import path from 'node:path';
import { outDir } from './trailer-lib.mjs';

const raw = path.join(outDir, 'raw', fs.readdirSync(path.join(outDir, 'raw')).find((f) => f.endsWith('.webm')));
const timeline = JSON.parse(fs.readFileSync(path.join(outDir, 'timeline.json'), 'utf8'));
const FONT = 'C\\:/Windows/Fonts/georgia.ttf', FONT_B = 'C\\:/Windows/Fonts/georgiab.ttf';
const captions = {
  hub: 'Harrow Cross  -  where every road meets',
  gear: 'Mythic gear. Expert rank. A bag worth robbing.',
  crags: 'Gloamstone Crags',
  glass: 'Glasswaste  -  the Mirage Reapers',
  starfall: 'Starfall Field',
  under: 'The Undercroft  -  the dark below',
  grave: 'Gravewatch  -  where the bosses wait',
  market: 'The Harrow Bazaar'
};
const trimFrom = Math.max(0, timeline[0].start - 0.6);
const total = timeline[timeline.length - 1].end - trimFrom;
const run = (args) => { const r = spawnSync('ffmpeg', ['-y', '-hide_banner', '-loglevel', 'error', ...args], { stdio: 'inherit' }); if (r.status !== 0) throw new Error('ffmpeg failed'); };

// 1. the gameplay with captions and fades
const draws = timeline.filter((s) => captions[s.name]).map((s) => {
  const a = (s.start - trimFrom + 1.6).toFixed(2), b = Math.min(s.end - trimFrom - 0.3, s.start - trimFrom + 6.2).toFixed(2);
  return `drawtext=fontfile='${FONT}':text='${captions[s.name].replace(/'/g, '')}':fontsize=34:fontcolor=white:box=1:boxcolor=0x0b0e18@0.72:boxborderw=16:x=(w-text_w)/2:y=h-118:enable='between(t,${a},${b})'`;
});
const vf = [...draws, `fade=t=in:d=0.6`, `fade=t=out:st=${(total - 0.7).toFixed(2)}:d=0.7`].join(',');
run(['-ss', trimFrom.toFixed(2), '-i', raw, '-t', total.toFixed(2), '-vf', vf, '-r', '25', '-c:v', 'libx264', '-crf', '17', '-preset', 'slow', '-pix_fmt', 'yuv420p', path.join(outDir, 'main.mp4')]);

// 2. title and end cards
const card = (file, title, sub, secs) => run(['-f', 'lavfi', '-i', `color=c=0x0b0e18:s=1280x720:r=25:d=${secs}`, '-vf',
  [`drawtext=fontfile='${FONT_B}':text='${title}':fontsize=96:fontcolor=0xf2c46b:x=(w-text_w)/2:y=(h-text_h)/2-40`,
    `drawtext=fontfile='${FONT}':text='${sub}':fontsize=34:fontcolor=0xd8d2c4:x=(w-text_w)/2:y=(h/2)+50`,
    `fade=t=in:d=0.8`, `fade=t=out:st=${secs - 0.8}:d=0.8`].join(','), '-c:v', 'libx264', '-crf', '17', '-pix_fmt', 'yuv420p', path.join(outDir, file)]);
card('intro.mp4', 'HALCYON REACH', 'An online RPG on the Morgana engine', 4);
card('outro.mp4', 'HALCYON REACH', 'Built with Morgana ORPG  -  runs in your browser', 4);

// 3. join
const list = path.join(outDir, 'concat.txt');
fs.writeFileSync(list, ['intro.mp4', 'main.mp4', 'outro.mp4'].map((f) => `file '${f}'`).join('\n'));
run(['-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', path.join(outDir, 'halcyon-reach-trailer.mp4')]);
console.log('wrote', path.join(outDir, 'halcyon-reach-trailer.mp4'));
