import {build} from 'esbuild';
import {copyFileSync,mkdirSync} from 'node:fs';
const out='app/src/main/assets';mkdirSync(out,{recursive:true});
await build({entryPoints:['src/main.js'],bundle:true,minify:true,format:'iife',target:'chrome90',outfile:out+'/game.js',legalComments:'eof'});
for(const name of ['index.html','style.css'])copyFileSync('src/'+name,out+'/'+name);
copyFileSync('node_modules/three/LICENSE',out+'/THREE-LICENSE.txt');
