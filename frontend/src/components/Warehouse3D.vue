<template>
  <div ref="containerRef" class="warehouse-3d"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, defineExpose } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js'
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js'

const containerRef = ref(null)

let renderer, scene, camera, controls, composer
let warehouseGroup, shuttlesGroup
let animationId
let shuttleMeshes = {}
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

function init() {
  const container = containerRef.value
  if (!container) return

  const width = container.clientWidth
  const height = container.clientHeight

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0a0a1a)
  scene.fog = new THREE.FogExp2(0x0a0a1a, 0.008)

  camera = new THREE.PerspectiveCamera(50, width / height, 0.1, 500)
  camera.position.set(50, 35, 50)
  camera.lookAt(0, 7.5, 0)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.2
  container.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.target.set(0, 7.5, 0)
  controls.minDistance = 10
  controls.maxDistance = 150
  controls.maxPolarAngle = Math.PI * 0.85

  composer = new EffectComposer(renderer)
  const renderPass = new RenderPass(scene, camera)
  composer.addPass(renderPass)

  const bloomPass = new UnrealBloomPass(
    new THREE.Vector2(width, height),
    1.2, 0.4, 0.2
  )
  composer.addPass(bloomPass)

  setupLights()
  buildWarehouse()
  createGroundGrid()

  window.addEventListener('resize', onResize)
  animate()
}

function setupLights() {
  const ambient = new THREE.AmbientLight(0x1a1a3e, 0.8)
  scene.add(ambient)

  const dirLight = new THREE.DirectionalLight(0x4466aa, 1.0)
  dirLight.position.set(30, 40, 20)
  dirLight.castShadow = true
  dirLight.shadow.mapSize.set(2048, 2048)
  dirLight.shadow.camera.left = -50
  dirLight.shadow.camera.right = 50
  dirLight.shadow.camera.top = 50
  dirLight.shadow.camera.bottom = -50
  scene.add(dirLight)

  const pointLight1 = new THREE.PointLight(0x4488ff, 0.6, 80)
  pointLight1.position.set(-15, 20, 0)
  scene.add(pointLight1)

  const pointLight2 = new THREE.PointLight(0xff4488, 0.4, 80)
  pointLight2.position.set(15, 20, 0)
  scene.add(pointLight2)

  const hemisphereLight = new THREE.HemisphereLight(0x223355, 0x0a0a1a, 0.3)
  scene.add(hemisphereLight)
}

function buildWarehouse() {
  warehouseGroup = new THREE.Group()
  shuttlesGroup = new THREE.Group()

  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2

  for (let level = 0; level < warehouseConfig.levels; level++) {
    const y = level * warehouseConfig.levelHeight
    buildLevel(y, level)
  }

  buildVerticalPillars(halfLength, halfWidth)

  scene.add(warehouseGroup)
  scene.add(shuttlesGroup)
}

function buildLevel(y, levelIndex) {
  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2
  const aisleSpacing = warehouseConfig.trackWidth / (warehouseConfig.aisles + 1)

  const floorGeom = new THREE.BoxGeometry(warehouseConfig.trackLength + 2, 0.15, warehouseConfig.trackWidth + 2)
  const floorMat = new THREE.MeshStandardMaterial({
    color: 0x1a1a2e,
    metalness: 0.6,
    roughness: 0.3,
    transparent: true,
    opacity: 0.85
  })
  const floor = new THREE.Mesh(floorGeom, floorMat)
  floor.position.set(0, y - 0.075, 0)
  floor.receiveShadow = true
  warehouseGroup.add(floor)

  const railMat = new THREE.MeshStandardMaterial({
    color: 0x334466,
    metalness: 0.8,
    roughness: 0.2,
    emissive: 0x112244,
    emissiveIntensity: 0.3
  })

  for (let a = 1; a <= warehouseConfig.aisles; a++) {
    const z = -halfWidth + a * aisleSpacing
    const railLong = new THREE.Mesh(
      new THREE.BoxGeometry(warehouseConfig.trackLength, 0.1, 0.15),
      railMat
    )
    railLong.position.set(0, y + 0.05, z)
    warehouseGroup.add(railLong)
  }

  const crossCount = Math.floor(warehouseConfig.trackLength / 4)
  for (let c = 0; c <= crossCount; c++) {
    const x = -halfLength + c * 4
    const railCross = new THREE.Mesh(
      new THREE.BoxGeometry(0.15, 0.1, warehouseConfig.trackWidth),
      railMat
    )
    railCross.position.set(x, y + 0.05, 0)
    warehouseGroup.add(railCross)
  }

  const shelfMat = new THREE.MeshStandardMaterial({
    color: 0x222244,
    metalness: 0.5,
    roughness: 0.5
  })

  const shelfWidth = aisleSpacing * 0.6
  const shelfHeight = warehouseConfig.levelHeight * 0.75
  const shelfDepth = 1.2

  for (let a = 0; a <= warehouseConfig.aisles; a++) {
    const z = -halfWidth + a * aisleSpacing + aisleSpacing / 2
    for (let sx = -halfLength + 2; sx <= halfLength - 2; sx += 4) {
      const shelf = new THREE.Mesh(
        new THREE.BoxGeometry(shelfDepth, shelfHeight, shelfWidth),
        shelfMat
      )
      shelf.position.set(sx, y + shelfHeight / 2, z)
      shelf.castShadow = true
      warehouseGroup.add(shelf)
    }
  }

  const levelLabel = createLevelLabel(levelIndex, y)
  warehouseGroup.add(levelLabel)
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

function buildVerticalPillars(halfLength, halfWidth) {
  const pillarMat = new THREE.MeshStandardMaterial({
    color: 0x333355,
    metalness: 0.7,
    roughness: 0.3
  })

  const pillarHeight = warehouseConfig.levels * warehouseConfig.levelHeight
  const positions = [
    [-halfLength, -halfWidth],
    [-halfLength, halfWidth],
    [halfLength, -halfWidth],
    [halfLength, halfWidth],
    [0, -halfWidth],
    [0, halfWidth]
  ]

  positions.forEach(([x, z]) => {
    const pillar = new THREE.Mesh(
      new THREE.BoxGeometry(0.4, pillarHeight, 0.4),
      pillarMat
    )
    pillar.position.set(x, pillarHeight / 2, z)
    pillar.castShadow = true
    warehouseGroup.add(pillar)
  })
}

function createGroundGrid() {
  const gridHelper = new THREE.GridHelper(100, 50, 0x112233, 0x0a1122)
  gridHelper.position.y = -0.5
  scene.add(gridHelper)
}

function createShuttleMesh(shuttleId) {
  const size = warehouseConfig.shuttleSize

  const group = new THREE.Group()

  const bodyGeom = new THREE.BoxGeometry(size, size * 0.5, size)
  const bodyMat = new THREE.MeshStandardMaterial({
    color: COLOR_EMPTY,
    emissive: COLOR_EMPTY,
    emissiveIntensity: 0.8,
    metalness: 0.3,
    roughness: 0.4,
    transparent: true,
    opacity: 0.95
  })
  const body = new THREE.Mesh(bodyGeom, bodyMat)
  body.castShadow = true
  group.add(body)

  const glowGeom = new THREE.BoxGeometry(size * 1.4, size * 0.7, size * 1.4)
  const glowMat = new THREE.MeshBasicMaterial({
    color: COLOR_EMPTY,
    transparent: true,
    opacity: 0.15,
    side: THREE.BackSide
  })
  const glow = new THREE.Mesh(glowGeom, glowMat)
  group.add(glow)

  const pointLight = new THREE.PointLight(COLOR_EMPTY, 0.5, 3)
  pointLight.position.set(0, 0.3, 0)
  group.add(pointLight)

  const canvas = document.createElement('canvas')
  canvas.width = 128
  canvas.height = 32
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#ffffff'
  ctx.font = 'bold 20px monospace'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(shuttleId.replace('SHUTTLE-', 'S'), 64, 16)

  const labelTexture = new THREE.CanvasTexture(canvas)
  const labelMat = new THREE.SpriteMaterial({ map: labelTexture, transparent: true, opacity: 0.7 })
  const label = new THREE.Sprite(labelMat)
  label.position.set(0, 0.6, 0)
  label.scale.set(1.5, 0.5, 1)
  group.add(label)

  group.userData = {
    shuttleId,
    bodyMesh: body,
    bodyMaterial: bodyMat,
    glowMesh: glow,
    glowMaterial: glowMat,
    pointLight,
    targetPosition: new THREE.Vector3(),
    currentPosition: new THREE.Vector3(),
    labelSprite: label
  }

  return group
}

function updateShuttleColor(meshGroup, hasLoad, status) {
  const data = meshGroup.userData
  let color

  if (status === 'CHARGING') {
    color = COLOR_CHARGING
  } else if (status === 'PICKING') {
    color = COLOR_PICKING
  } else if (status === 'DROPPING') {
    color = COLOR_DROPPING
  } else if (hasLoad) {
    color = COLOR_LOADED
  } else {
    color = COLOR_EMPTY
  }

  data.bodyMaterial.color.copy(color)
  data.bodyMaterial.emissive.copy(color)
  data.glowMaterial.color.copy(color)
  data.pointLight.color.copy(color)
}

function updateShuttles(shuttleStatuses) {
  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2

  shuttleStatuses.forEach(status => {
    const id = status.shuttleId

    if (!shuttleMeshes[id]) {
      const mesh = createShuttleMesh(id)
      mesh.position.set(
        status.x - halfLength,
        status.y,
        status.z - halfWidth
      )
      mesh.userData.currentPosition.set(mesh.position.x, mesh.position.y, mesh.position.z)
      mesh.userData.targetPosition.set(mesh.position.x, mesh.position.y, mesh.position.z)
      shuttlesGroup.add(mesh)
      shuttleMeshes[id] = mesh
    }

    const mesh = shuttleMeshes[id]
    const targetX = status.x - halfLength
    const targetY = status.y
    const targetZ = status.z - halfWidth

    mesh.userData.targetPosition.set(targetX, targetY, targetZ)
    updateShuttleColor(mesh, status.hasLoad, status.status)
    mesh.userData.batteryLevel = status.batteryLevel
    mesh.userData.speed = status.speed
  })
}

function setTrajectoryPoints(points) {
  clearTrajectoryPoints()

  if (!points || points.length < 2) return

  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2

  const positions = []
  points.forEach(p => {
    positions.push(p.x - halfLength, p.y + 0.3, p.z - halfWidth)
  })

  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))

  const material = new THREE.LineBasicMaterial({
    color: 0x00ffcc,
    transparent: true,
    opacity: 0.7,
    linewidth: 2
  })

  const line = new THREE.Line(geometry, material)
  line.name = '__trajectory_line__'
  scene.add(line)

  points.forEach((p, i) => {
    const dotGeom = new THREE.SphereGeometry(0.1, 8, 8)
    const dotMat = new THREE.MeshBasicMaterial({
      color: i === 0 ? 0x00ff00 : i === points.length - 1 ? 0xff0000 : 0x00ffcc,
      transparent: true,
      opacity: 0.6
    })
    const dot = new THREE.Mesh(dotGeom, dotMat)
    dot.position.set(p.x - halfLength, p.y + 0.3, p.z - halfWidth)
    dot.name = '__trajectory_dot__'
    scene.add(dot)
  })
}

function clearTrajectoryPoints() {
  const toRemove = []
  scene.traverse(child => {
    if (child.name === '__trajectory_line__' || child.name === '__trajectory_dot__') {
      toRemove.push(child)
    }
  })
  toRemove.forEach(obj => {
    if (obj.geometry) obj.geometry.dispose()
    if (obj.material) obj.material.dispose()
    scene.remove(obj)
  })
}

function focusOnShuttle(shuttleId) {
  const mesh = shuttleMeshes[shuttleId]
  if (!mesh) return

  const pos = mesh.position.clone()
  controls.target.copy(pos)
  camera.position.set(pos.x + 15, pos.y + 12, pos.z + 15)
}

function animate() {
  animationId = requestAnimationFrame(animate)

  const delta = clock.getDelta()

  Object.values(shuttleMeshes).forEach(mesh => {
    const data = mesh.userData
    const current = data.currentPosition
    const target = data.targetPosition

    const lerpFactor = Math.min(1, delta * 8)
    current.lerp(target, lerpFactor)

    mesh.position.copy(current)

    const dx = target.x - current.x
    const dz = target.z - current.z
    if (Math.abs(dx) > 0.01 || Math.abs(dz) > 0.01) {
      mesh.rotation.y = Math.atan2(dx, dz)
    }

    const pulse = Math.sin(clock.elapsedTime * 3 + mesh.id) * 0.1 + 0.9
    data.bodyMaterial.emissiveIntensity = 0.6 + pulse * 0.4
    data.glowMaterial.opacity = 0.1 + pulse * 0.08
  })

  controls.update()
  composer.render()
}

function onResize() {
  const container = containerRef.value
  if (!container || !renderer) return

  const width = container.clientWidth
  const height = container.clientHeight

  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height)
  composer.setSize(width, height)
}

function dispose() {
  window.removeEventListener('resize', onResize)

  if (animationId) {
    cancelAnimationFrame(animationId)
  }

  Object.values(shuttleMeshes).forEach(mesh => {
    mesh.traverse(child => {
      if (child.geometry) child.geometry.dispose()
      if (child.material) {
        if (child.material.map) child.material.map.dispose()
        child.material.dispose()
      }
    })
  })

  if (warehouseGroup) {
    warehouseGroup.traverse(child => {
      if (child.geometry) child.geometry.dispose()
      if (child.material) child.material.dispose()
    })
  }

  if (renderer) {
    renderer.dispose()
  }

  shuttleMeshes = {}
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
