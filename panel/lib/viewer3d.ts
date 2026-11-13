// The 3D view: EngineHub's schematic viewer, with the changes marked on top, an asset's anchor and facing, and a click
// to inspect a block. Loaded only when "3D" is picked, since it brings Babylon.js.
import { renderSchematic, type SchematicHandles } from '@enginehub/schematicwebviewer';
import { type ArcRotateCamera, Color3, Color4, EngineStore, InstancedMesh, Matrix, Mesh, MeshBuilder, type Scene, StandardMaterial, Vector3 } from '@babylonjs/core';
import { anchorInside, type Cell, FACING_STEP } from './anchor';
import { cells, type Diff, type Kind, toSchematic, type View } from './schematic';
import { modelOverrides } from './modelResources';
import type { Facing } from './types';

// The same colours as IsoRenderer and the legend.
const BACKGROUND = 0x1E2228;
const MARK: Record<Kind, string> = { ADDED: '#3FD16B', REMOVED: '#E5484D', CHANGED: '#F2C94C' };
// An asset's anchor cell and facing arrow: a colour none of the change marks use.
const ANCHOR = '#38BDF8';

export interface Picked {
  pos: [number, number, number];
  /** The same block from the box's lowest corner (an asset's own coordinates). */
  cell: Cell;
  before: string;
  after: string;
  kind?: Kind;
}

export interface Viewer {
  /** Draws a view; resolves once it shows. A later call replaces an earlier one still loading. */
  show(view: View): Promise<void>;
  /** Marks an asset's anchor cell (null or outside the box: none) and draws an arrow the way it faces. */
  setAnchor(cell: Cell | null, facing: Facing | null): void;
  dispose(): void;
}

export function supported(): boolean {
  try {
    const gl = document.createElement('canvas').getContext('webgl2');
    gl?.getExtension('WEBGL_lose_context')?.loseContext();
    return !!gl;
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
  let loading = Promise.resolve();
  let jar: Promise<string[]> | null = null;
  const resourceUrls: string[] = [];
  const abort = new AbortController();

  // Check HTTP errors ourselves: the library otherwise tries to unzip the panel's JSON error response.
  // Keep the blob for this viewer's lifetime so redraws need not fetch it again.
  const resources = () => jar ??= (async () => {
    const response = await fetch(jarUrl, { signal: abort.signal });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new Error(body?.error || `Could not load Minecraft textures (${response.status}).`);
    }
    const blob = await response.blob();
    const overrides = await modelOverrides(await blob.arrayBuffer());
    if (disposed) throw new Error('Viewer closed.');
    // The library searches its first jar before resource packs, so put the small compatibility pack first.
    if (overrides) resourceUrls.push(URL.createObjectURL(overrides));
    resourceUrls.push(URL.createObjectURL(blob));
    return resourceUrls;
  })();

  const size = () => ({ width: Math.max(1, holder.clientWidth), height: Math.max(1, holder.clientHeight) });
  const resize = new ResizeObserver(() => {
    const { width: w, height: h } = size();
    current?.handles.setSize(w, h);
  });
  resize.observe(holder);

  const scene = (): Scene => current!.handles.getEngine().scenes[0];

  // The viewer centres the schematic on the origin, one unit per block, with Minecraft's axes (+x east, +z south).
  const at = (x: number, y: number, z: number): [number, number, number] =>
    [x - width / 2 + 0.5, y - height / 2 + 0.5, z - length / 2 + 0.5];
  const centre = (index: number) => at(index % width, Math.floor(index / (width * length)), Math.floor(index / width) % length);

  let anchor: { cell: Cell | null; facing: Facing | null } = { cell: null, facing: null };
  // The marker in the scene shown now.
  let anchorMeshes: Mesh[] = [];

  /**
   * Draws the anchor marker into a scene and returns its meshes. They go in rendering group 1, drawn after the blocks
   * with the depth cleared, so the marker shows even when the anchor cell is hidden inside the asset.
   */
  function drawAnchor(s: Scene): Mesh[] {
    const meshes: Mesh[] = [];
    const { facing } = anchor;
    const cell = anchor.cell && anchorInside(anchor.cell, [width, height, length]) ? anchor.cell : null;
    const add = (mesh: Mesh, alpha: number) => {
      const material = new StandardMaterial(mesh.name, s);
      material.emissiveColor = Color3.FromHexString(ANCHOR);
      material.disableLighting = true;
      material.alpha = alpha;
      mesh.material = material;
      mesh.isPickable = false;
      mesh.renderingGroupId = 1;
      meshes.push(mesh);
    };
    // Without an anchor the arrow starts at the middle of the asset's side it faces.
    const from = cell ? new Vector3(...at(...cell)) : Vector3.Zero();
    if (cell) {
      const box = MeshBuilder.CreateBox('anchor-cell', { size: 1.06 }, s);
      box.position.copyFrom(from);
      add(box, 0.3);
      box.enableEdgesRendering();
      box.edgesWidth = 6;
      box.edgesColor = Color4.FromHexString(ANCHOR + 'FF');
    }
    if (facing) {
      const step = new Vector3(...FACING_STEP[facing]);
      const half = cell ? 0.5 : (Math.abs(step.x) * width + Math.abs(step.y) * height + Math.abs(step.z) * length) / 2;
      const start = from.add(step.scale(half)), neck = start.add(step.scale(1.4)), tip = start.add(step.scale(2));
      add(MeshBuilder.CreateTube('anchor-shaft', { path: [start, neck], radius: 0.07, tessellation: 12, cap: Mesh.CAP_ALL }, s), 1);
      add(MeshBuilder.CreateTube('anchor-head', {
        path: [neck, tip], radiusFunction: i => i === 0 ? 0.25 : 0, tessellation: 16, cap: Mesh.CAP_START,
      }, s), 1);
    }
    s.createOrUpdateSelectionOctree();
    return meshes;
  }

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
    const start = down;
    down = null;
    if (!current || !start || Math.hypot(e.clientX - start[0], e.clientY - start[1]) > 4) return;
    const rect = current.canvas.getBoundingClientRect();
    const s = scene();
    const hit = s.pick(e.clientX - rect.left, e.clientY - rect.top);
    if (!hit.hit || !hit.pickedMesh || !hit.pickedPoint) return onPick(null);
    const normal = hit.getNormal(true);
    // Each model element is an instance of a hidden prototype. Its translation relative to that prototype is
    // the exact cell centre, even for thin/rotated models or a hit precisely on an edge between two cells.
    const p = hit.pickedMesh instanceof InstancedMesh
      ? hit.pickedMesh.position.subtract(hit.pickedMesh.sourceMesh.position)
      : normal ? hit.pickedPoint.subtract(normal.scale(0.01)) : hit.pickedPoint;
    const x = Math.floor(p.x + width / 2), y = Math.floor(p.y + height / 2), z = Math.floor(p.z + length / 2);
    if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) return onPick(null);
    const index = (y * length + z) * width + x;
    onPick({
      pos: [diff.origin[0] + x, diff.origin[1] + y, diff.origin[2] + z],
      cell: [x, y, z],
      before: diff.palette[diff.before[index]],
      after: diff.palette[diff.after[index]],
      kind: diff.kinds.get(index),
    });
  };

  async function draw(view: View, mine: number) {
    if (disposed || mine !== shown) return;
    const [url, ...packs] = await resources();
    const schematic = await toSchematic(diff, cells(diff, view));
    if (disposed || mine !== shown) return;
    const canvas = document.createElement('canvas');
    let handles: SchematicHandles;
    try {
      handles = await renderSchematic(canvas, schematic, {
        size: size(),
        getClientJarUrl: async () => {
          // This hook runs after Scene/Camera creation but before any model meshes/materials are built. Match
          // Minecraft's handedness here, so a north-facing camera has east on the left, like the PNG elevation.
          const s = EngineStore.Instances.find(engine => engine.getRenderingCanvas() === canvas)!.scenes[0];
          s.useRightHandedSystem = true;
          s.activeCamera?.getViewMatrix(true);
          s.activeCamera?.getProjectionMatrix(true);
          return url;
        },
        resourcePacks: packs,
        orbit: false,
        antialias: true,
        backgroundColor: BACKGROUND,
      });
    } catch (ex) {
      // The library creates its engine before loading models, but returns no handles on failure.
      EngineStore.Instances.find(engine => engine.getRenderingCanvas() === canvas)?.dispose();
      throw ex;
    }
    if (disposed || mine !== shown) {
      handles.destroy();
      return;
    }
    const s = handles.getEngine().scenes[0];
    let marker: Mesh[];
    try {
      // The library's Intermediate performance preset makes every model/instance unpickable by default.
      for (const mesh of s.meshes) mesh.isPickable = mesh.isVisible && mesh.isEnabled();
      if (view.mode === 'changes') mark(s, view);
      marker = drawAnchor(s);
      // Keep the camera where the user left it.
      if (current) {
        const was = scene().activeCamera as ArcRotateCamera, now = s.activeCamera as ArcRotateCamera;
        now.alpha = was.alpha;
        now.beta = was.beta;
        now.radius = was.radius;
        now.target.copyFrom(was.target);
      }
    } catch (ex) {
      handles.destroy();
      throw ex;
    }
    current?.handles.destroy();
    const { width: w, height: h } = size();
    handles.setSize(w, h);
    canvas.addEventListener('pointerdown', onDown);
    canvas.addEventListener('pointerup', onUp);
    holder.replaceChildren(canvas);
    current = { canvas, handles };
    // The old scene's marker went with that scene.
    anchorMeshes = marker;
    onPick(null);
  }

  return {
    show(view) {
      const mine = ++shown;
      // Do not parse several client jars/create several WebGL contexts at once when controls change quickly.
      const next = loading.then(() => draw(view, mine));
      loading = next.catch(() => {});
      return next;
    },
    setAnchor(cell, facing) {
      anchor = { cell, facing };
      if (!current) return;
      anchorMeshes.forEach(m => m.dispose(false, true));
      anchorMeshes = drawAnchor(scene());
    },
    dispose() {
      disposed = true;
      abort.abort();
      resourceUrls.forEach(url => URL.revokeObjectURL(url));
      resize.disconnect();
      current?.handles.destroy();
      current = null;
      holder.replaceChildren();
    },
  };
}
