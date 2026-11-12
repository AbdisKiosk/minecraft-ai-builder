// The 3D view: EngineHub's schematic viewer, with the changes marked on top and a click to inspect a block.
// Loaded only when "3D" is picked, since it brings Babylon.js.
import { renderSchematic, type SchematicHandles } from '@enginehub/schematicwebviewer';
import { type ArcRotateCamera, Color3, Matrix, MeshBuilder, type Scene, StandardMaterial } from '@babylonjs/core';
import { cells, type Diff, type Kind, toSchematic, type View } from './schematic';

// The same colours as IsoRenderer and the legend.
const BACKGROUND = 0x1E2228;
const MARK: Record<Kind, string> = { ADDED: '#3FD16B', REMOVED: '#E5484D', CHANGED: '#F2C94C' };

export interface Picked {
  pos: [number, number, number];
  before: string;
  after: string;
  kind?: Kind;
}

export interface Viewer {
  /** Draws a view; resolves once it shows. A later call replaces an earlier one still loading. */
  show(view: View): Promise<void>;
  dispose(): void;
}

export function supported(): boolean {
  try {
    return !!document.createElement('canvas').getContext('webgl2');
  } catch {
    return false;
  }
}

/**
 * @param jarUrl the Minecraft client jar the viewer takes block models and textures from
 * @param onPick called with the block clicked, or null for a click on nothing
 */
export function createViewer(holder: HTMLElement, diff: Diff, jarUrl: string, onPick: (p: Picked | null) => void): Viewer {
  const { width, height, length } = diff;
  let current: { canvas: HTMLCanvasElement; handles: SchematicHandles } | null = null;
  let shown = 0;
  let disposed = false;

  const size = () => ({ width: Math.max(1, holder.clientWidth), height: Math.max(1, holder.clientHeight) });
  const resize = new ResizeObserver(() => {
    const { width: w, height: h } = size();
    current?.handles.setSize(w, h);
  });
  resize.observe(holder);

  const scene = (): Scene => current!.handles.getEngine().scenes[0];

  // The viewer centres the schematic on the origin, one unit per block.
  const centre = (index: number) => {
    const x = index % width, z = Math.floor(index / width) % length, y = Math.floor(index / (width * length));
    return [x - width / 2 + 0.5, y - height / 2 + 0.5, z - length / 2 + 0.5];
  };

  function mark(s: Scene, view: View) {
    const byKind = new Map<Kind, number[]>();
    const top = (view.cut + 1) * width * length;
    for (const [index, kind] of diff.kinds) {
      if (index >= top) continue;
      if (!byKind.has(kind)) byKind.set(kind, []);
      byKind.get(kind)!.push(index);
    }
    for (const [kind, indexes] of byKind) {
      const box = MeshBuilder.CreateBox(`mark-${kind}`, { size: 1.04 }, s);
      const material = new StandardMaterial(`mark-${kind}`, s);
      material.emissiveColor = Color3.FromHexString(MARK[kind]);
      material.disableLighting = true;
      material.alpha = 0.45;
      box.material = material;
      box.isPickable = false;
      const matrices = new Float32Array(indexes.length * 16);
      indexes.forEach((index, i) => {
        const [x, y, z] = centre(index);
        Matrix.Translation(x, y, z).copyToArray(matrices, i * 16);
      });
      box.thinInstanceSetBuffer('matrix', matrices, 16, true);
    }
    // The viewer builds a selection octree before returning; meshes added after it are only drawn once it is rebuilt.
    s.createOrUpdateSelectionOctree();
  }

  let down: [number, number] | null = null;
  const onDown = (e: PointerEvent) => { down = [e.clientX, e.clientY]; };
  const onUp = (e: PointerEvent) => {
    if (!current || !down || Math.hypot(e.clientX - down[0], e.clientY - down[1]) > 4) return;
    const rect = current.canvas.getBoundingClientRect();
    const s = scene();
    const hit = s.pick(e.clientX - rect.left, e.clientY - rect.top);
    const normal = hit.hit ? hit.getNormal(true) : null;
    if (!hit.pickedPoint || !normal) return onPick(null);
    // Step just inside the face that was hit, then into the cell grid.
    const p = hit.pickedPoint.subtract(normal.scale(0.01));
    const x = Math.floor(p.x + width / 2), y = Math.floor(p.y + height / 2), z = Math.floor(p.z + length / 2);
    if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) return onPick(null);
    const index = (y * length + z) * width + x;
    onPick({
      pos: [diff.origin[0] + x, diff.origin[1] + y, diff.origin[2] + z],
      before: diff.palette[diff.before[index]],
      after: diff.palette[diff.after[index]],
      kind: diff.kinds.get(index),
    });
  };

  return {
    async show(view) {
      const mine = ++shown;
      const schematic = await toSchematic(diff, cells(diff, view));
      const canvas = document.createElement('canvas');
      const handles = await renderSchematic(canvas, schematic, {
        size: size(),
        getClientJarUrl: async () => jarUrl,
        orbit: false,
        antialias: true,
        backgroundColor: BACKGROUND,
      });
      if (disposed || mine !== shown) {
        handles.destroy();
        return;
      }
      const s = handles.getEngine().scenes[0];
      if (view.mode === 'changes') mark(s, view);
      // Keep the camera where the user left it.
      if (current) {
        const was = scene().activeCamera as ArcRotateCamera, now = s.activeCamera as ArcRotateCamera;
        now.alpha = was.alpha;
        now.beta = was.beta;
        now.radius = was.radius;
        now.target.copyFrom(was.target);
        current.handles.destroy();
      }
      canvas.addEventListener('pointerdown', onDown);
      canvas.addEventListener('pointerup', onUp);
      holder.replaceChildren(canvas);
      current = { canvas, handles };
      onPick(null);
    },
    dispose() {
      disposed = true;
      resize.disconnect();
      current?.handles.destroy();
      current = null;
      holder.replaceChildren();
    },
  };
}
