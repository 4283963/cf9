<template>
  <div ref="containerRef" class="warehouse-3d"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, defineExpose } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'

const containerRef = ref(null)

const MAX_SHUTTLES = 64
const MAX_TRAJECTORY_POINTS = 5000

let renderer, scene, camera, controls
let warehouseGroup
let animationId
let clock = new THREE.Clock()

const COLOR_EMPTY = new THREE.Color(0x00ff88)
const COLOR_LOADED = new THREE.Color(0xffcc00)
const COLOR_CHARGING = new THREE.Color(0xff4444)
const COLOR_PICKING = new THREE.Color(0x44aaff)
const COLOR_DROPPING = new THREE.Color(0xff8844)

const warehouseConfig = {
  levels: 5,
  aisles: 5,
  trackLength: 40,
  trackWidth: 20,
  levelHeight: 3.0,
  locationsPerAisle: 20,
  shuttleSize: 0.6
}

const shuttleIndexMap = new Map()
const shuttleDataArray = new Array(MAX_SHUTTLES)
for (let i = 0; i < MAX_SHUTTLES; i++) {
  shuttleDataArray[i] = {
    shuttleId: null,
    currentPos: new THREE.Vector3(),
    targetPos: new THREE.Vector3(),
    rotation: 0,
    hasLoad: false,
    status: 'MOVING',
    active: false
  }
}
let activeShuttleCount = 0

let shuttleInstancedMesh = null
const dummy = new THREE.Object3D()
const tempColor = new THREE.Color()

let trajectoryLine = null
let trajectoryStartMarker = null
let trajectoryEndMarker = null
let trajectoryGeo = null
let trajectoryMat = null
let markerGeo = null
let startMarkerMat = null
let endMarkerMat = null

let sharedShuttleGeo = null
let sharedShuttleMat = null

function init() {
  const container = containerRef.value
  if (!container) return

  const width = container.clientWidth
  const height = container.clientHeight

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0a0a1a)
  scene.fog = new THREE.FogExp2(0x0a0a1a, 0.006)

  camera = new THREE.PerspectiveCamera(50, width / height, 0.1, 500)
  camera.position.set(50, 35, 50)
  camera.lookAt(0, 7.5, 0)

  renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.0
  container.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.target.set(0, 7.5, 0)
  controls.minDistance = 10
  controls.maxDistance = 150
  controls.maxPolarAngle = Math.PI * 0.85

  setupLights()
  buildWarehouse()
  createGroundGrid()
  createShuttleInstances()
  createTrajectoryPool()

  window.addEventListener('resize', onResize)
  animate()
}

function setupLights() {
  scene.add(new THREE.AmbientLight(0x1a1a3e, 1.0))

  const dirLight = new THREE.DirectionalLight(0x4466aa, 1.2)
  dirLight.position.set(30, 40, 20)
  scene.add(dirLight)

  const hemisphereLight = new THREE.HemisphereLight(0x223355, 0x0a0a1a, 0.4)
  scene.add(hemisphereLight)
}

function buildWarehouse() {
  warehouseGroup = new THREE.Group()

  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2

  const sharedFloorGeo = new THREE.BoxGeometry(warehouseConfig.trackLength + 2, 0.15, warehouseConfig.trackWidth + 2)
  const sharedFloorMat = new THREE.MeshStandardMaterial({
    color: 0x1a1a2e,
    metalness: 0.6,
    roughness: 0.3,
    transparent: true,
    opacity: 0.85
  })

  const sharedRailGeo = new THREE.BoxGeometry(warehouseConfig.trackLength, 0.1, 0.15)
  const sharedCrossGeo = new THREE.BoxGeometry(0.15, 0.1, warehouseConfig.trackWidth)
  const sharedRailMat = new THREE.MeshStandardMaterial({
    color: 0x334466,
    metalness: 0.8,
    roughness: 0.2,
    emissive: 0x112244,
    emissiveIntensity: 0.3
  })

  const sharedShelfGeo = new THREE.BoxGeometry(1.2, warehouseConfig.levelHeight * 0.75, warehouseConfig.trackWidth / (warehouseConfig.aisles + 1) * 0.6)
  const sharedShelfMat = new THREE.MeshStandardMaterial({
    color: 0x222244,
    metalness: 0.5,
    roughness: 0.5
  })

  for (let level = 0; level < warehouseConfig.levels; level++) {
    const y = level * warehouseConfig.levelHeight

    const floor = new THREE.Mesh(sharedFloorGeo, sharedFloorMat)
    floor.position.set(0, y - 0.075, 0)
    warehouseGroup.add(floor)

    const aisleSpacing = warehouseConfig.trackWidth / (warehouseConfig.aisles + 1)
    for (let a = 1; a <= warehouseConfig.aisles; a++) {
      const railLong = new THREE.Mesh(sharedRailGeo, sharedRailMat)
      railLong.position.set(0, y + 0.05, -halfWidth + a * aisleSpacing)
      warehouseGroup.add(railLong)
    }

    const crossCount = Math.floor(warehouseConfig.trackLength / 4)
    for (let c = 0; c <= crossCount; c++) {
      const railCross = new THREE.Mesh(sharedCrossGeo, sharedRailMat)
      railCross.position.set(-halfLength + c * 4, y + 0.05, 0)
      warehouseGroup.add(railCross)
    }

    for (let a = 0; a <= warehouseConfig.aisles; a++) {
      const z = -halfWidth + a * aisleSpacing + aisleSpacing / 2
      for (let sx = -halfLength + 2; sx <= halfLength - 2; sx += 4) {
        const shelf = new THREE.Mesh(sharedShelfGeo, sharedShelfMat)
        shelf.position.set(sx, y + sharedShelfGeo.parameters.height / 2, z)
        warehouseGroup.add(shelf)
      }
    }

    const levelLabel = createLevelLabel(level, y)
    warehouseGroup.add(levelLabel)
  }

  const sharedPillarGeo = new THREE.BoxGeometry(0.4, warehouseConfig.levels * warehouseConfig.levelHeight, 0.4)
  const sharedPillarMat = new THREE.MeshStandardMaterial({ color: 0x333355, metalness: 0.7, roughness: 0.3 })
  const pillarPositions = [[-halfLength, -halfWidth], [-halfLength, halfWidth], [halfLength, -halfWidth], [halfLength, halfWidth], [0, -halfWidth], [0, halfWidth]]
  pillarPositions.forEach(([x, z]) => {
    const pillar = new THREE.Mesh(sharedPillarGeo, sharedPillarMat)
    pillar.position.set(x, sharedPillarGeo.parameters.height / 2, z)
    warehouseGroup.add(pillar)
  })

  scene.add(warehouseGroup)
}

function createLevelLabel(levelIndex, y) {
  const canvas = document.createElement('canvas')
  canvas.width = 128
  canvas.height = 64
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#00aaff'
  ctx.font = 'bold 32px monospace'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(`L${levelIndex}`, 64, 32)

  const texture = new THREE.CanvasTexture(canvas)
  const spriteMat = new THREE.SpriteMaterial({ map: texture, transparent: true, opacity: 0.8 })
  const sprite = new THREE.Sprite(spriteMat)
  sprite.position.set(-warehouseConfig.trackLength / 2 - 2, y + 1.5, 0)
  sprite.scale.set(3, 1.5, 1)
  return sprite
}

function createGroundGrid() {
  const gridHelper = new THREE.GridHelper(100, 50, 0x112233, 0x0a1122)
  gridHelper.position.y = -0.5
  scene.add(gridHelper)
}

function createShuttleInstances() {
  const size = warehouseConfig.shuttleSize
  sharedShuttleGeo = new THREE.BoxGeometry(size, size * 0.5, size)
  sharedShuttleMat = new THREE.MeshStandardMaterial({
    color: 0xffffff,
    emissive: 0x00ff88,
    emissiveIntensity: 0.6,
    metalness: 0.3,
    roughness: 0.4,
    transparent: true,
    opacity: 0.95
  })

  shuttleInstancedMesh = new THREE.InstancedMesh(sharedShuttleGeo, sharedShuttleMat, MAX_SHUTTLES)
  shuttleInstancedMesh.count = 0
  shuttleInstancedMesh.frustumCulled = false

  for (let i = 0; i < MAX_SHUTTLES; i++) {
    dummy.position.set(0, -100, 0)
    dummy.updateMatrix()
    shuttleInstancedMesh.setMatrixAt(i, dummy.matrix)
    shuttleInstancedMesh.setColorAt(i, COLOR_EMPTY)
  }
  shuttleInstancedMesh.instanceMatrix.needsUpdate = true
  shuttleInstancedMesh.instanceColor.needsUpdate = true

  scene.add(shuttleInstancedMesh)
}

function createTrajectoryPool() {
  trajectoryGeo = new THREE.BufferGeometry()
  const emptyPositions = new Float32Array(MAX_TRAJECTORY_POINTS * 3)
  trajectoryGeo.setAttribute('position', new THREE.BufferAttribute(emptyPositions, 3))
  trajectoryGeo.setDrawRange(0, 0)

  trajectoryMat = new THREE.LineBasicMaterial({
    color: 0x00ffcc,
    transparent: true,
    opacity: 0.7
  })
  trajectoryLine = new THREE.Line(trajectoryGeo, trajectoryMat)
  trajectoryLine.visible = false
  trajectoryLine.frustumCulled = false
  scene.add(trajectoryLine)

  markerGeo = new THREE.SphereGeometry(0.15, 6, 6)
  startMarkerMat = new THREE.MeshBasicMaterial({ color: 0x00ff00 })
  endMarkerMat = new THREE.MeshBasicMaterial({ color: 0xff0000 })

  trajectoryStartMarker = new THREE.Mesh(markerGeo, startMarkerMat)
  trajectoryStartMarker.visible = false
  scene.add(trajectoryStartMarker)

  trajectoryEndMarker = new THREE.Mesh(markerGeo, endMarkerMat)
  trajectoryEndMarker.visible = false
  scene.add(trajectoryEndMarker)
}

function getShuttleColor(hasLoad, status) {
  if (status === 'CHARGING') return COLOR_CHARGING
  if (status === 'PICKING') return COLOR_PICKING
  if (status === 'DROPPING') return COLOR_DROPPING
  return hasLoad ? COLOR_LOADED : COLOR_EMPTY
}

function updateShuttles(shuttleStatuses) {
  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2

  shuttleStatuses.forEach(status => {
    const id = status.shuttleId

    let idx = shuttleIndexMap.get(id)
    if (idx === undefined) {
      if (activeShuttleCount >= MAX_SHUTTLES) return
      idx = activeShuttleCount++
      shuttleIndexMap.set(id, idx)
      const data = shuttleDataArray[idx]
      data.shuttleId = id
      data.active = true
      const tx = status.x - halfLength
      const ty = status.y
      const tz = status.z - halfWidth
      data.currentPos.set(tx, ty, tz)
      data.targetPos.set(tx, ty, tz)
      data.rotation = 0
    }

    const data = shuttleDataArray[idx]
    data.targetPos.set(status.x - halfLength, status.y, status.z - halfWidth)
    data.hasLoad = status.hasLoad
    data.status = status.status

    tempColor.copy(getShuttleColor(status.hasLoad, status.status))
    shuttleInstancedMesh.setColorAt(idx, tempColor)
  })

  shuttleInstancedMesh.count = activeShuttleCount
  shuttleInstancedMesh.instanceColor.needsUpdate = true
}

function setTrajectoryPoints(points) {
  if (!points || points.length < 2) {
    clearTrajectoryPoints()
    return
  }

  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2

  const capped = points.length > MAX_TRAJECTORY_POINTS
    ? points.filter((_, i) => i % Math.ceil(points.length / MAX_TRAJECTORY_POINTS) === 0 || i === points.length - 1)
    : points

  const posAttr = trajectoryGeo.getAttribute('position')
  const arr = posAttr.array

  for (let i = 0; i < capped.length; i++) {
    arr[i * 3] = capped[i].x - halfLength
    arr[i * 3 + 1] = capped[i].y + 0.3
    arr[i * 3 + 2] = capped[i].z - halfWidth
  }

  posAttr.needsUpdate = true
  trajectoryGeo.setDrawRange(0, capped.length)
  trajectoryLine.visible = true

  const first = capped[0]
  trajectoryStartMarker.position.set(first.x - halfLength, first.y + 0.3, first.z - halfWidth)
  trajectoryStartMarker.visible = true

  const last = capped[capped.length - 1]
  trajectoryEndMarker.position.set(last.x - halfLength, last.y + 0.3, last.z - halfWidth)
  trajectoryEndMarker.visible = true
}

function clearTrajectoryPoints() {
  if (trajectoryGeo) {
    trajectoryGeo.setDrawRange(0, 0)
  }
  if (trajectoryLine) {
    trajectoryLine.visible = false
  }
  if (trajectoryStartMarker) {
    trajectoryStartMarker.visible = false
  }
  if (trajectoryEndMarker) {
    trajectoryEndMarker.visible = false
  }
}

function focusOnShuttle(shuttleId) {
  const idx = shuttleIndexMap.get(shuttleId)
  if (idx === undefined) return

  const pos = shuttleDataArray[idx].currentPos
  controls.target.copy(pos)
  camera.position.set(pos.x + 15, pos.y + 12, pos.z + 15)
}

function animate() {
  animationId = requestAnimationFrame(animate)

  const delta = clock.getDelta()
  const elapsed = clock.elapsedTime

  const pulse = Math.sin(elapsed * 3) * 0.15 + 0.85
  sharedShuttleMat.emissiveIntensity = 0.5 + pulse * 0.3

  for (let i = 0; i < activeShuttleCount; i++) {
    const data = shuttleDataArray[i]
    const current = data.currentPos
    const target = data.targetPos

    current.lerp(target, Math.min(1, delta * 8))

    dummy.position.copy(current)

    const dx = target.x - current.x
    const dz = target.z - current.z
    if (Math.abs(dx) > 0.01 || Math.abs(dz) > 0.01) {
      data.rotation = Math.atan2(dx, dz)
    }
    dummy.rotation.set(0, data.rotation, 0)
    dummy.updateMatrix()
    shuttleInstancedMesh.setMatrixAt(i, dummy.matrix)
  }

  shuttleInstancedMesh.instanceMatrix.needsUpdate = true

  controls.update()
  renderer.render(scene, camera)
}

function onResize() {
  const container = containerRef.value
  if (!container || !renderer) return

  const width = container.clientWidth
  const height = container.clientHeight

  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height)
}

function dispose() {
  window.removeEventListener('resize', onResize)

  if (animationId) {
    cancelAnimationFrame(animationId)
    animationId = null
  }

  shuttleIndexMap.clear()
  activeShuttleCount = 0

  if (sharedShuttleGeo) { sharedShuttleGeo.dispose(); sharedShuttleGeo = null }
  if (sharedShuttleMat) { sharedShuttleMat.dispose(); sharedShuttleMat = null }
  if (shuttleInstancedMesh) {
    shuttleInstancedMesh.dispose()
    scene.remove(shuttleInstancedMesh)
    shuttleInstancedMesh = null
  }

  if (trajectoryGeo) { trajectoryGeo.dispose(); trajectoryGeo = null }
  if (trajectoryMat) { trajectoryMat.dispose(); trajectoryMat = null }
  if (trajectoryLine) { scene.remove(trajectoryLine); trajectoryLine = null }
  if (markerGeo) { markerGeo.dispose(); markerGeo = null }
  if (startMarkerMat) { startMarkerMat.dispose(); startMarkerMat = null }
  if (endMarkerMat) { endMarkerMat.dispose(); endMarkerMat = null }
  if (trajectoryStartMarker) { scene.remove(trajectoryStartMarker); trajectoryStartMarker = null }
  if (trajectoryEndMarker) { scene.remove(trajectoryEndMarker); trajectoryEndMarker = null }

  if (warehouseGroup) {
    warehouseGroup.traverse(child => {
      if (child.geometry) child.geometry.dispose()
      if (child.material) {
        if (child.material.map) child.material.map.dispose()
        child.material.dispose()
      }
    })
    scene.remove(warehouseGroup)
    warehouseGroup = null
  }

  if (renderer) {
    renderer.dispose()
    renderer = null
  }

  if (controls) {
    controls.dispose()
    controls = null
  }
}

onMounted(() => {
  init()
})

onBeforeUnmount(() => {
  dispose()
})

defineExpose({
  updateShuttles,
  setTrajectoryPoints,
  clearTrajectoryPoints,
  focusOnShuttle,
  getWarehouseConfig: () => warehouseConfig
})
</script>
