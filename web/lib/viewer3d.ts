// Interactive 3D view of a history entry or draft, for the Pictures card. Loaded only when "3D" is picked.
// The data comes from /blocks (DiffImages.encode in the plugin): palette, run-length before/after, and the changes.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

type Kind = 'ADDED' | 'REMOVED' | 'CHANGED';
type SubBox = number[];

/** What /blocks sends. */
export interface BlockData {
  box: number[];
  palette: { state: string; rgb: number; transparent?: boolean; boxes?: SubBox[] }[];
  before: [number, number][];
  after: [number, number][];
  changes: [number, Kind][];
}

export interface Viewer { dispose(): void }

// The same colours as IsoRenderer.
const BACKGROUND = 0x1E2228;
const TINT = { ADDED: 0x3FD16B, REMOVED: 0xE5484D, CHANGED: 0xF2C94C };
const KIND: Record<Kind, number> = { ADDED: 1, REMOVED: 2, CHANGED: 3 };
const KIND_NAME = ['', 'added', 'removed', 'changed'];
const KIND_TINT = [0, TINT.ADDED, TINT.REMOVED, TINT.CHANGED];
const FULL: SubBox[] = [[0, 0, 0, 1, 1, 1]];

function mix(rgb: number, toward: number, f: number): number {
  const r = ((rgb >> 16) & 255) * (1 - f) + ((toward >> 16) & 255) * f;
  const g = ((rgb >> 8) & 255) * (1 - f) + ((toward >> 8) & 255) * f;
  const b = (rgb & 255) * (1 - f) + (toward & 255) * f;
  return (r << 16) | (g << 8) | b;
}

function expand(runs: [number, number][], volume: number): Uint32Array {
  const out = new Uint32Array(volume);
  let i = 0;
  for (const [index, count] of runs) {
    out.fill(index, i, i + count);
    i += count;
  }
  return out;
}

export function supported(): boolean {
  try {
    return !!document.createElement('canvas').getContext('webgl2');
  } catch {
    return false;
  }
}

/** Draws {@code data} into {@code el}. Call dispose() on the result before removing the element. */
export function mount(el: HTMLElement, data: BlockData): Viewer {
  const [x1, y1, z1, x2, y2, z2] = data.box;
  const sx = x2 - x1 + 1, sy = y2 - y1 + 1, sz = z2 - z1 + 1;
  const volume = sx * sy * sz;
  const palette = data.palette;
  const before = expand(data.before, volume);
  const after = expand(data.after, volume);
  const kinds = new Uint8Array(volume);
  let changeMin = [Infinity, Infinity, Infinity], changeMax = [-Infinity, -Infinity, -Infinity];
  for (const [index, kind] of data.changes) {
    kinds[index] = KIND[kind];
    const p = [index % sx, Math.floor(index / (sx * sz)), Math.floor(index / sx) % sz];
    for (let a = 0; a < 3; a++) {
      changeMin[a] = Math.min(changeMin[a], p[a]);
      changeMax[a] = Math.max(changeMax[a], p[a]);
    }
  }
  if (!data.changes.length) { changeMin = [0, 0, 0]; changeMax = [sx - 1, sy - 1, sz - 1]; }
  // What the Changes view draws: the new blocks, with removed ones where they were.
  const marked = after.slice();
  for (let i = 0; i < volume; i++) if (kinds[i] === KIND.REMOVED) marked[i] = before[i];

  const drawn = palette.map(p => p.rgb >= 0);
  const solid = palette.map(p => p.rgb >= 0 && !p.transparent && !p.boxes);

  // ---------- controls ----------
  el.innerHTML = `<div class="v3-bar">
      <span class="seg" role="group" aria-label="Show">
        <button type="button" data-mode="before">Before</button><button type="button" data-mode="after">After</button><button type="button" data-mode="changes">Changes</button>
      </span>
      <label class="v3-hide"><input type="checkbox"> only changed blocks</label>
      <label class="v3-cut">Cut above y <input type="range" min="${y1}" max="${y2}" step="1"> <output></output></label>
      <span class="spacer"></span>
      <button type="button" class="ghost v3-reset">Reset view</button>
    </div>
    <div class="v3-stage"></div>
    <div class="v3-info"></div>`;
  const stage = el.querySelector<HTMLElement>('.v3-stage')!;
  const info = el.querySelector<HTMLElement>('.v3-info')!;
  const hide = el.querySelector<HTMLInputElement>('.v3-hide input')!;
  const cut = el.querySelector<HTMLInputElement>('.v3-cut input')!;
  const cutOut = el.querySelector<HTMLOutputElement>('.v3-cut output')!;
  const hint = 'Drag to rotate, right-drag to pan, scroll to zoom. Click a block for its coordinates.';
  let mode = 'changes';
  // One above the highest change, so an edit inside a building shows without its roof.
  cut.value = String(Math.min(y2, y1 + changeMax[1] + 1));

  // ---------- scene ----------
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(BACKGROUND);
  stage.appendChild(renderer.domElement);
  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 1.9));
  const sun = new THREE.DirectionalLight(0xffffff, 1.6);
  sun.position.set(0.5, 1, 0.8);
  scene.add(sun);
  const camera = new THREE.PerspectiveCamera(45, 1, 0.5, 4000);
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.zoomToCursor = true;
  const cube = new THREE.BoxGeometry(1, 1, 1);
  const opaqueMaterial = new THREE.MeshLambertMaterial();
  const glassMaterial = new THREE.MeshLambertMaterial({ transparent: true, opacity: 0.45, depthWrite: false });
  const outline = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1.04, 1.04, 1.04)),
      new THREE.LineBasicMaterial({ color: 0xffffff }));
  outline.visible = false;
  scene.add(outline);
  // Minecraft and three.js are both right-handed with y up: x is east, z is south. The box is centred on the origin.
  const ox = -sx / 2, oy = -sy / 2, oz = -sz / 2;
  let meshes: THREE.InstancedMesh[] = [];
  let queued = 0;

  function draw() {
    if (queued) return;
    queued = requestAnimationFrame(() => {
      queued = 0;
      renderer.render(scene, camera);
    });
  }

  function rebuild() {
    for (const m of meshes) { scene.remove(m); m.dispose(); }
    meshes = [];
    const changes = mode === 'changes';
    const cells = mode === 'before' ? before : changes ? marked : after;
    const onlyChanged = changes && hide.checked;
    const layers = Number(cut.value) - y1 + 1;
    const layer = sx * sz;
    const shows = (i: number) => drawn[cells[i]] && !(onlyChanged && !kinds[i]);
    const hides = (i: number) => solid[cells[i]] && !(onlyChanged && !kinds[i]);
    // Instances: [cell, sub-box] pairs, for opaque blocks and for see-through ones.
    const lists: number[][] = [[], []];
    for (let y = 0, i = 0; y < layers; y++) {
      for (let z = 0; z < sz; z++) {
        for (let x = 0; x < sx; x++, i++) {
          if (!shows(i)) continue;
          // A block with a solid cube on every side cannot be seen.
          if (x > 0 && x < sx - 1 && z > 0 && z < sz - 1 && y > 0 && y < layers - 1
              && hides(i - 1) && hides(i + 1) && hides(i - sx) && hides(i + sx) && hides(i - layer) && hides(i + layer)) continue;
          const p = palette[cells[i]];
          const list = lists[p.transparent && !(changes && kinds[i]) ? 1 : 0];
          const n = p.boxes ? p.boxes.length : 1;
          for (let b = 0; b < n; b++) list.push(i, b);
        }
      }
    }
    const color = new THREE.Color();
    lists.forEach((list, which) => {
      const count = list.length / 2;
      if (!count) return;
      const mesh = new THREE.InstancedMesh(cube, which ? glassMaterial : opaqueMaterial, count);
      const matrix = mesh.instanceMatrix.array;
      const colors = new Float32Array(count * 3);
      const cellOf = new Uint32Array(count);
      for (let n = 0; n < count; n++) {
        const i = list[n * 2];
        const x = i % sx, z = Math.floor(i / sx) % sz, y = Math.floor(i / layer);
        const p = palette[cells[i]];
        const b = (p.boxes || FULL)[list[n * 2 + 1]];
        const o = n * 16;
        matrix[o] = b[3] - b[0];
        matrix[o + 5] = b[4] - b[1];
        matrix[o + 10] = b[5] - b[2];
        matrix[o + 12] = ox + x + (b[0] + b[3]) / 2;
        matrix[o + 13] = oy + y + (b[1] + b[4]) / 2;
        matrix[o + 14] = oz + z + (b[2] + b[5]) / 2;
        matrix[o + 15] = 1;
        let rgb = p.rgb;
        if (changes) rgb = kinds[i] ? mix(rgb, KIND_TINT[kinds[i]], 0.65) : mix(rgb, BACKGROUND, 0.6);
        // A little variation from block to block, or a flat wall of one material shows no blocks at all.
        const shade = 0.94 + 0.12 * ((((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) >>> 0) % 7) / 6;
        color.setHex(rgb).multiplyScalar(shade).toArray(colors, n * 3);
        cellOf[n] = i;
      }
      mesh.instanceColor = new THREE.InstancedBufferAttribute(colors, 3);
      mesh.userData.cellOf = cellOf;
      mesh.renderOrder = which;
      mesh.computeBoundingSphere();
      scene.add(mesh);
      meshes.push(mesh);
    });
    el.querySelectorAll<HTMLButtonElement>('.seg button').forEach(b => b.classList.toggle('on', b.dataset.mode === mode));
    hide.disabled = !changes;
    cutOut.textContent = cut.value;
    outline.visible = false;
    info.textContent = hint;
    draw();
  }

  function resetView() {
    const c = [0, 1, 2].map(a => (changeMin[a] + changeMax[a] + 1) / 2);
    const span = Math.hypot(...[0, 1, 2].map(a => changeMax[a] - changeMin[a] + 1));
    controls.target.set(ox + c[0], oy + c[1], oz + c[2]);
    // From the south-east, like the default picture.
    camera.position.copy(controls.target).add(new THREE.Vector3(1, 0.9, 1).normalize().multiplyScalar(Math.max(14, span * 1.5)));
    controls.update();
    draw();
  }

  function resize() {
    const w = stage.clientWidth, h = stage.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    draw();
  }

  // ---------- picking ----------
  const raycaster = new THREE.Raycaster();
  let down: [number, number] | null = null;
  const onDown = (e: PointerEvent) => { down = [e.clientX, e.clientY]; };
  const onUp = (e: PointerEvent) => {
    if (!down || Math.hypot(e.clientX - down[0], e.clientY - down[1]) > 4) return;
    const r = renderer.domElement.getBoundingClientRect();
    raycaster.setFromCamera(new THREE.Vector2((e.clientX - r.left) / r.width * 2 - 1, -(e.clientY - r.top) / r.height * 2 + 1), camera);
    const hit = raycaster.intersectObjects(meshes, false)[0];
    if (!hit) {
      outline.visible = false;
      info.textContent = hint;
      return draw();
    }
    const i: number = hit.object.userData.cellOf[hit.instanceId!];
    const x = i % sx, z = Math.floor(i / sx) % sz, y = Math.floor(i / (sx * sz));
    outline.position.set(ox + x + 0.5, oy + y + 0.5, oz + z + 0.5);
    outline.visible = true;
    const was = palette[before[i]].state, now = palette[after[i]].state;
    info.textContent = '';
    const pos = document.createElement('b');
    pos.textContent = `${x1 + x} ${y1 + y} ${z1 + z}`;
    info.append(pos, kinds[i] ? `  ${was} → ${now}  (${KIND_NAME[kinds[i]]})` : `  ${now}  (unchanged)`);
    draw();
  };

  el.querySelectorAll<HTMLButtonElement>('.seg button').forEach(b => { b.onclick = () => { mode = b.dataset.mode!; rebuild(); }; });
  hide.onchange = rebuild;
  cut.oninput = rebuild;
  el.querySelector<HTMLButtonElement>('.v3-reset')!.onclick = resetView;
  controls.addEventListener('change', draw);
  renderer.domElement.addEventListener('pointerdown', onDown);
  renderer.domElement.addEventListener('pointerup', onUp);
  const observer = new ResizeObserver(resize);
  observer.observe(stage);

  rebuild();
  resize();
  resetView();

  return {
    dispose() {
      observer.disconnect();
      if (queued) cancelAnimationFrame(queued);
      controls.dispose();
      for (const m of meshes) m.dispose();
      cube.dispose();
      opaqueMaterial.dispose();
      glassMaterial.dispose();
      outline.geometry.dispose();
      outline.material.dispose();
      renderer.dispose();
      renderer.forceContextLoss();
      el.innerHTML = '';
    },
  };
}
