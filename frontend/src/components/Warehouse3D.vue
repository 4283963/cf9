<template>
  <div ref="wrapperRef" class="warehouse-3d-wrapper">
    <div ref="containerRef" class="warehouse-3d"></div>
    <div
      v-if="selectedShuttle"
      ref="faultPanelRef"
      class="fault-panel"
      :style="{ left: panelScreenPos.x + 'px', top: panelScreenPos.y + 'px', display: panelVisible ? 'block' : 'none' }"
      @click.stop
      @mousedown.stop
    >
      <div class="fault-panel-header">
        <span class="panel-title">
          <span class="warning-icon">⚠</span>
          {{ selectedShuttle.shuttleId }} 故障诊断看板
        </span>
        <button class="panel-close-btn" @click="closePanel">✕</button>
      </div>
      <div class="fault-panel-body">
        <div class="fault-info-grid">
          <div class="info-item">
            <span class="info-label">故障类型</span>
            <span class="info-value fault-value">{{ selectedShuttle.faultType || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">严重程度</span>
            <div class="severity-bar">
              <div class="severity-fill" :style="{ width: (selectedShuttle.faultSeverity * 100).toFixed(0) + '%' }"></div>
            </div>
          </div>
          <div class="info-item">
            <span class="info-label">撞轨值</span>
            <span class="info-value">{{ (selectedShuttle.collisionValue || 0).toFixed(2) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">当前电流</span>
            <span class="info-value current-value">{{ (selectedShuttle.currentLoad || 0).toFixed(2) }} A</span>
          </div>
          <div class="info-item">
            <span class="info-label">电量</span>
            <span class="info-value">{{ (selectedShuttle.batteryLevel || 0).toFixed(1) }}%</span>
          </div>
          <div class="info-item">
            <span class="info-label">位置</span>
            <span class="info-value">L{{ selectedShuttle.level }}-A{{ selectedShuttle.aisle }}</span>
          </div>
        </div>
        <div class="chart-section">
          <div class="chart-title">电流负荷疲劳退化曲线 (最近 {{ chartMinutes }} 分钟)</div>
          <div ref="chartRef" class="chart-canvas-wrap">
            <canvas ref="chartCanvasRef"></canvas>
          </div>
          <div class="chart-legend">
            <span class="legend-item"><span class="legend-dot" style="background:#44aaff"></span>电流 (A)</span>
            <span class="legend-item"><span class="legend-dot" style="background:#ff4444"></span>阈值 (10A)</span>
            <span class="legend-item"><span class="legend-dot" style="background:#ffcc00"></span>故障区间</span>
          </div>
          <div class="diagnosis-hint" v-if="diagnosisText">
            <span class="hint-icon">💡</span>{{ diagnosisText }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, defineExpose, computed } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { Chart, registerables } from 'chart.js'
import { getCurrentLoadHistory } from '../services/ApiService.js'

Chart.register(...registerables)

const containerRef = ref(null)
const wrapperRef = ref(null)
const faultPanelRef = ref(null)
const chartCanvasRef = ref(null)

const MAX_SHUTTLES = 64
const MAX_TRAJECTORY_POINTS = 5000

let renderer, scene, camera, controls
let warehouseGroup
let animationId
let clock = new THREE.Clock()
let raycaster = new THREE.Raycaster()
let mouseVec = new THREE.Vector2()
let screenProjectionVec = new THREE.Vector3()

const COLOR_EMPTY = new THREE.Color(0x00ff88)
const COLOR_LOADED = new THREE.Color(0xffcc00)
const COLOR_CHARGING = new THREE.Color(0xff4444)
const COLOR_PICKING = new THREE.Color(0x44aaff)
const COLOR_DROPPING = new THREE.Color(0xff8844)
const COLOR_FAULT = new THREE.Color(0xff2222)

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
    hasFault: false,
    faultType: null,
    faultSeverity: 0,
    collisionValue: 0,
    currentLoad: 0,
    batteryLevel: 0,
    level: 0,
    aisle: 0,
    active: false
  }
}
let activeShuttleCount = 0

let shuttleInstancedMesh = null
const dummy = new THREE.Object3D()
const tempColor = new THREE.Color()
let faultFlash = 0

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

let levelWarningPlanes = []
let levelWarningMaterials = []
const levelFaultStates = new Map()

const selectedShuttle = reactive({
  shuttleId: null,
  level: 0,
  aisle: 0,
  x: 0,
  y: 0,
  z: 0,
  hasLoad: false,
  status: 'MOVING',
  batteryLevel: 0,
  currentLoad: 0,
  hasFault: false,
  faultType: null,
  faultSeverity: 0,
  collisionValue: 0
})
const panelScreenPos = reactive({ x: 0, y: 0 })
const panelVisible = ref(false)
const chartMinutes = ref(10)
let chartInstance = null
let currentDataCache = []
let chartRefreshTimer = null

const diagnosisText = computed(() => {
  if (!currentDataCache || currentDataCache.length === 0) return ''
  const avg = currentDataCache.reduce((s, p) => s + (p.currentLoad || 0), 0) / currentDataCache.length
  const faultPoints = currentDataCache.filter(p => p.hasFault).length
  const overThreshold = currentDataCache.filter(p => (p.currentLoad || 0) >= 10).length

  if (avg > 10 && overThreshold > currentDataCache.length * 0.4) {
    return '⚠ 电流持续超载 + 故障频率高，疑似电机绕组烧毁或轴承抱死，建议立即断电检修！'
  } else if (avg > 8 && faultPoints > 0) {
    return '⚠ 电流偏高伴随间歇故障，可能是传动机构磨损或电池老化，请尽快排查。'
  } else if (faultPoints > 0) {
    return '⚠ 检测到碰撞触发，建议检查轨道异物及定位传感器校准。'
  } else if (avg > 7) {
    return 'ⓘ 电流负荷处于正常偏高区间，建议持续观察。'
  }
  return 'ⓘ 电流负荷处于正常区间。'
})

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
  createLevelWarningPlanes()
  createShuttleInstances()
  createTrajectoryPool()

  window.addEventListener('resize', onResize)
  renderer.domElement.addEventListener('click', onCanvasClick)

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
    color: 0x1a1a2e, metalness: 0.6, roughness: 0.3, transparent: true, opacity: 0.85
  })
  const sharedRailGeo = new THREE.BoxGeometry(warehouseConfig.trackLength, 0.1, 0.15)
  const sharedCrossGeo = new THREE.BoxGeometry(0.15, 0.1, warehouseConfig.trackWidth)
  const sharedRailMat = new THREE.MeshStandardMaterial({
    color: 0x334466, metalness: 0.8, roughness: 0.2, emissive: 0x112244, emissiveIntensity: 0.3
  })
  const sharedShelfGeo = new THREE.BoxGeometry(1.2, warehouseConfig.levelHeight * 0.75, warehouseConfig.trackWidth / (warehouseConfig.aisles + 1) * 0.6)
  const sharedShelfMat = new THREE.MeshStandardMaterial({ color: 0x222244, metalness: 0.5, roughness: 0.5 })

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

function createLevelWarningPlanes() {
  const halfLength = warehouseConfig.trackLength / 2
  const halfWidth = warehouseConfig.trackWidth / 2
  const planeGeo = new THREE.PlaneGeometry(warehouseConfig.trackLength + 3, warehouseConfig.trackWidth + 3)

  for (let level = 0; level < warehouseConfig.levels; level++) {
    const mat = new THREE.MeshBasicMaterial({
      color: 0xff0000,
      transparent: true,
      opacity: 0,
      depthWrite: false,
      side: THREE.DoubleSide
    })
    const plane = new THREE.Mesh(planeGeo, mat)
    plane.rotation.x = -Math.PI / 2
    plane.position.set(0, level * warehouseConfig.levelHeight + 0.25, 0)
    plane.visible = false
    levelWarningPlanes.push(plane)
    levelWarningMaterials.push(mat)
    levelFaultStates.set(level, { hasFault: false, maxSeverity: 0 })
    scene.add(plane)
  }
}

function updateLevelWarnings(shuttleStatuses) {
  for (let level = 0; level < warehouseConfig.levels; level++) {
    levelFaultStates.set(level, { hasFault: false, maxSeverity: 0 })
  }

  shuttleStatuses.forEach(s => {
    if (s.hasFault && typeof s.level === 'number') {
      const state = levelFaultStates.get(s.level) || { hasFault: false, maxSeverity: 0 }
      state.hasFault = true
      state.maxSeverity = Math.max(state.maxSeverity, s.faultSeverity || 0.5)
      levelFaultStates.set(s.level, state)
    }
  })

  const flash = faultFlash
  for (let level = 0; level < levelWarningPlanes.length; level++) {
    const state = levelFaultStates.get(level)
    const plane = levelWarningPlanes[level]
    const mat = levelWarningMaterials[level]
    if (state && state.hasFault) {
      const baseOpacity = 0.25 + state.maxSeverity * 0.35
      mat.opacity = baseOpacity * (0.7 + flash * 0.3)
      plane.visible = true
    } else {
      mat.opacity = 0
      plane.visible = false
    }
  }
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

  trajectoryMat = new THREE.LineBasicMaterial({ color: 0x00ffcc, transparent: true, opacity: 0.7 })
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

function getShuttleColor(hasLoad, status, hasFault, faultFlashOn) {
  if (hasFault) {
    if (faultFlashOn) {
      return COLOR_FAULT
    }
    return new THREE.Color(0xff6644)
  }
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
    data.hasFault = !!status.hasFault
    data.faultType = status.faultType
    data.faultSeverity = status.faultSeverity || 0
    data.collisionValue = status.collisionValue || 0
    data.currentLoad = status.currentLoad || 0
    data.batteryLevel = status.batteryLevel || 0
    data.level = status.level
    data.aisle = status.aisle

    tempColor.copy(getShuttleColor(status.hasLoad, status.status, !!status.hasFault, faultFlash > 0.5))
    shuttleInstancedMesh.setColorAt(idx, tempColor)

    if (selectedShuttle.shuttleId === id) {
      Object.assign(selectedShuttle, {
        x: status.x - halfLength,
        y: status.y,
        z: status.z - halfWidth,
        hasLoad: status.hasLoad,
        status: status.status,
        batteryLevel: status.batteryLevel || 0,
        currentLoad: status.currentLoad || 0,
        hasFault: !!status.hasFault,
        faultType: status.faultType,
        faultSeverity: status.faultSeverity || 0,
        collisionValue: status.collisionValue || 0,
        level: status.level,
        aisle: status.aisle
      })
    }
  })

  shuttleInstancedMesh.count = activeShuttleCount
  shuttleInstancedMesh.instanceColor.needsUpdate = true

  updateLevelWarnings(shuttleStatuses)
  if (panelVisible.value && selectedShuttle.shuttleId) {
    updatePanelPosition()
  }
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
  if (trajectoryGeo) trajectoryGeo.setDrawRange(0, 0)
  if (trajectoryLine) trajectoryLine.visible = false
  if (trajectoryStartMarker) trajectoryStartMarker.visible = false
  if (trajectoryEndMarker) trajectoryEndMarker.visible = false
}

function onCanvasClick(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  mouseVec.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  mouseVec.y = -((event.clientY - rect.top) / rect.height) * 2 + 1

  raycaster.setFromCamera(mouseVec, camera)
  const intersects = raycaster.intersectObject(shuttleInstancedMesh)

  if (intersects.length > 0 && intersects[0].instanceId !== undefined) {
    const idx = intersects[0].instanceId
    const data = shuttleDataArray[idx]
    if (data && data.shuttleId && data.active) {
      openPanelForShuttle(data, idx)
    }
  } else {
    closePanel()
  }
}

function openPanelForShuttle(data, idx) {
  selectedShuttle.shuttleId = data.shuttleId
  selectedShuttle.level = data.level
  selectedShuttle.aisle = data.aisle
  selectedShuttle.x = data.currentPos.x
  selectedShuttle.y = data.currentPos.y
  selectedShuttle.z = data.currentPos.z
  selectedShuttle.hasLoad = data.hasLoad
  selectedShuttle.status = data.status
  selectedShuttle.batteryLevel = data.batteryLevel
  selectedShuttle.currentLoad = data.currentLoad
  selectedShuttle.hasFault = data.hasFault
  selectedShuttle.faultType = data.faultType
  selectedShuttle.faultSeverity = data.faultSeverity
  selectedShuttle.collisionValue = data.collisionValue

  panelVisible.value = true
  nextTick(() => {
    initChart()
    loadCurrentHistory()
    if (chartRefreshTimer) clearInterval(chartRefreshTimer)
    chartRefreshTimer = setInterval(loadCurrentHistory, 5000)
  })
  updatePanelPosition()
}

function closePanel() {
  panelVisible.value = false
  if (chartRefreshTimer) {
    clearInterval(chartRefreshTimer)
    chartRefreshTimer = null
  }
  if (chartInstance) {
    chartInstance.destroy()
    chartInstance = null
  }
  currentDataCache = []
}

function focusOnShuttle(shuttleId) {
  const idx = shuttleIndexMap.get(shuttleId)
  if (idx === undefined) return
  const pos = shuttleDataArray[idx].currentPos
  controls.target.copy(pos)
  camera.position.set(pos.x + 15, pos.y + 12, pos.z + 15)
}

function updatePanelPosition() {
  if (!selectedShuttle.shuttleId) return
  screenProjectionVec.set(selectedShuttle.x, selectedShuttle.y + 1.2, selectedShuttle.z)
  screenProjectionVec.project(camera)

  const container = containerRef.value
  if (!container) return
  const widthHalf = container.clientWidth / 2
  const heightHalf = container.clientHeight / 2

  let screenX = screenProjectionVec.x * widthHalf + widthHalf
  let screenY = -screenProjectionVec.y * heightHalf + heightHalf

  screenX = Math.max(10, Math.min(screenX, container.clientWidth - 330))
  screenY = Math.max(10, Math.min(screenY, container.clientHeight - 430))

  panelScreenPos.x = screenX
  panelScreenPos.y = screenY
}

function initChart() {
  if (!chartCanvasRef.value) return
  const ctx = chartCanvasRef.value.getContext('2d')
  if (chartInstance) {
    chartInstance.destroy()
  }

  chartInstance = new Chart(ctx, {
    type: 'line',
    data: {
      labels: [],
      datasets: [{
        label: '电流负荷 (A)',
        data: [],
        borderColor: 'rgba(68, 170, 255, 1)',
        backgroundColor: 'rgba(68, 170, 255, 0.15)',
        borderWidth: 2,
        fill: true,
        tension: 0.25,
        pointRadius: 0,
        pointHitRadius: 6
      }, {
        label: '阈值 (10A)',
        data: [],
        borderColor: 'rgba(255, 68, 68, 0.7)',
        borderDash: [6, 4],
        borderWidth: 2,
        fill: false,
        pointRadius: 0
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: 'rgba(10, 10, 26, 0.95)',
          titleColor: '#fff',
          bodyColor: '#88ddff',
          borderColor: '#223355',
          borderWidth: 1,
          padding: 10,
          callbacks: {
            afterBody: (items) => {
              if (!items || items.length === 0) return ''
              const idx = items[0].dataIndex
              const point = currentDataCache[idx]
              if (!point) return ''
              const lines = []
              if (point.hasFault) lines.push('⚠ 故障触发')
              if (point.collisionValue > 0) lines.push(`撞轨: ${point.collisionValue.toFixed(2)}`)
              if (point.faultSeverity > 0) lines.push(`严重度: ${(point.faultSeverity * 100).toFixed(0)}%`)
              return lines.join('\n')
            }
          }
        }
      },
      scales: {
        x: {
          ticks: { color: '#667799', maxTicksLimit: 6, maxRotation: 0 },
          grid: { color: 'rgba(80, 100, 140, 0.1)' }
        },
        y: {
          min: 0,
          max: 18,
          ticks: { color: '#667799' },
          grid: { color: 'rgba(80, 100, 140, 0.1)' }
        }
      }
    }
  })
}

async function loadCurrentHistory() {
  if (!selectedShuttle.shuttleId) return
  try {
    const data = await getCurrentLoadHistory(selectedShuttle.shuttleId, chartMinutes.value, 600)
    currentDataCache = data

    if (chartInstance) {
      chartInstance.data.labels = data.map(p => {
        const t = new Date(p.timestamp)
        return `${t.getHours().toString().padStart(2, '0')}:${t.getMinutes().toString().padStart(2, '0')}:${t.getSeconds().toString().padStart(2, '0')}`
      })
      chartInstance.data.datasets[0].data = data.map(p => p.currentLoad || 0)
      chartInstance.data.datasets[1].data = data.map(() => 10)
      chartInstance.data.datasets[0].backgroundColor = buildBackgroundGradient(data)
      chartInstance.update('none')
    }
  } catch (e) {
    console.warn('加载电流历史失败:', e.message)
  }
}

function buildBackgroundGradient(data) {
  if (!chartCanvasRef.value || !chartInstance) return 'rgba(68, 170, 255, 0.15)'
  const ctx = chartCanvasRef.value.getContext('2d')
  const gradient = ctx.createLinearGradient(0, 0, 0, 200)
  gradient.addColorStop(0, 'rgba(255, 68, 68, 0.35)')
  gradient.addColorStop(0.45, 'rgba(255, 200, 80, 0.2)')
  gradient.addColorStop(1, 'rgba(68, 200, 130, 0.1)')
  return gradient
}

function animate() {
  animationId = requestAnimationFrame(animate)
  const delta = clock.getDelta()
  const elapsed = clock.elapsedTime

  faultFlash = (Math.sin(elapsed * 6) + 1) / 2

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

    if (data.hasFault) {
      const scale = 1 + faultFlash * 0.15
      dummy.scale.set(scale, scale, scale)
    } else {
      dummy.scale.set(1, 1, 1)
    }

    dummy.updateMatrix()
    shuttleInstancedMesh.setMatrixAt(i, dummy.matrix)

    if (data.hasFault) {
      tempColor.copy(faultFlash > 0.5 ? COLOR_FAULT : new THREE.Color(0xff6644))
      shuttleInstancedMesh.setColorAt(i, tempColor)
    }
  }

  shuttleInstancedMesh.instanceMatrix.needsUpdate = true
  if (faultFlash < 0.02 || faultFlash > 0.98) {
    shuttleInstancedMesh.instanceColor.needsUpdate = true
  }

  for (let level = 0; level < levelWarningPlanes.length; level++) {
    const state = levelFaultStates.get(level)
    if (state && state.hasFault) {
      const mat = levelWarningMaterials[level]
      const baseOpacity = 0.25 + state.maxSeverity * 0.35
      mat.opacity = baseOpacity * (0.7 + faultFlash * 0.3)
    }
  }

  if (panelVisible.value && selectedShuttle.shuttleId) {
    updatePanelPosition()
  }

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
  if (renderer && renderer.domElement) {
    renderer.domElement.removeEventListener('click', onCanvasClick)
  }
  if (animationId) { cancelAnimationFrame(animationId); animationId = null }
  if (chartRefreshTimer) { clearInterval(chartRefreshTimer); chartRefreshTimer = null }
  if (chartInstance) { chartInstance.destroy(); chartInstance = null }

  shuttleIndexMap.clear()
  activeShuttleCount = 0
  levelFaultStates.clear()

  if (sharedShuttleGeo) { sharedShuttleGeo.dispose(); sharedShuttleGeo = null }
  if (sharedShuttleMat) { sharedShuttleMat.dispose(); sharedShuttleMat = null }
  if (shuttleInstancedMesh) { shuttleInstancedMesh.dispose(); scene.remove(shuttleInstancedMesh); shuttleInstancedMesh = null }

  if (trajectoryGeo) { trajectoryGeo.dispose(); trajectoryGeo = null }
  if (trajectoryMat) { trajectoryMat.dispose(); trajectoryMat = null }
  if (trajectoryLine) { scene.remove(trajectoryLine); trajectoryLine = null }
  if (markerGeo) { markerGeo.dispose(); markerGeo = null }
  if (startMarkerMat) { startMarkerMat.dispose(); startMarkerMat = null }
  if (endMarkerMat) { endMarkerMat.dispose(); endMarkerMat = null }
  if (trajectoryStartMarker) { scene.remove(trajectoryStartMarker); trajectoryStartMarker = null }
  if (trajectoryEndMarker) { scene.remove(trajectoryEndMarker); trajectoryEndMarker = null }

  levelWarningPlanes.forEach(p => { scene.remove(p) })
  levelWarningPlanes = []
  levelWarningMaterials.forEach(m => m.dispose())
  levelWarningMaterials = []

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
  if (renderer) { renderer.dispose(); renderer = null }
  if (controls) { controls.dispose(); controls = null }
}

onMounted(() => { init() })
onBeforeUnmount(() => { dispose() })

defineExpose({
  updateShuttles,
  setTrajectoryPoints,
  clearTrajectoryPoints,
  focusOnShuttle,
  getWarehouseConfig: () => warehouseConfig
})
</script>

<style scoped>
.warehouse-3d-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.warehouse-3d {
  width: 100%;
  height: 100%;
}

.fault-panel {
  position: absolute;
  width: 300px;
  background: rgba(15, 18, 32, 0.96);
  border: 1px solid #ff4444;
  border-radius: 8px;
  box-shadow: 0 8px 32px rgba(255, 60, 60, 0.4), 0 0 24px rgba(255, 60, 60, 0.2);
  backdrop-filter: blur(8px);
  z-index: 1000;
  color: #ddeeff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  pointer-events: auto;
  user-select: none;
  overflow: hidden;
}

.fault-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: linear-gradient(90deg, rgba(255, 50, 50, 0.25), rgba(255, 100, 50, 0.1));
  border-bottom: 1px solid rgba(255, 80, 80, 0.3);
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #ff9090;
  display: flex;
  align-items: center;
  gap: 6px;
}

.warning-icon {
  color: #ffcc00;
  font-size: 15px;
  animation: pulse-icon 1s infinite;
}

@keyframes pulse-icon {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(1.2); }
}

.panel-close-btn {
  background: transparent;
  border: none;
  color: #8899aa;
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}
.panel-close-btn:hover { background: rgba(255, 80, 80, 0.2); color: #fff; }

.fault-panel-body {
  padding: 12px;
}

.fault-info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
  margin-bottom: 12px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.info-label {
  font-size: 11px;
  color: #667788;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.info-value {
  font-size: 14px;
  font-weight: 600;
  color: #88ddff;
  font-family: 'SF Mono', Consolas, monospace;
}

.info-value.fault-value {
  color: #ff6666;
  text-transform: uppercase;
}

.info-value.current-value {
  color: #ffaa44;
}

.severity-bar {
  width: 100%;
  height: 8px;
  background: rgba(50, 50, 80, 0.6);
  border-radius: 4px;
  overflow: hidden;
  margin-top: 3px;
}

.severity-fill {
  height: 100%;
  background: linear-gradient(90deg, #ffcc00, #ff4444);
  border-radius: 4px;
  transition: width 0.3s;
}

.chart-section {
  border-top: 1px solid rgba(60, 80, 120, 0.3);
  padding-top: 10px;
}

.chart-title {
  font-size: 12px;
  color: #88aacc;
  margin-bottom: 6px;
  font-weight: 600;
}

.chart-canvas-wrap {
  width: 100%;
  height: 140px;
  position: relative;
}

.chart-legend {
  display: flex;
  gap: 10px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.legend-item {
  font-size: 11px;
  color: #8899aa;
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.diagnosis-hint {
  margin-top: 10px;
  padding: 8px 10px;
  background: rgba(255, 180, 50, 0.08);
  border-left: 3px solid #ffaa44;
  border-radius: 0 4px 4px 0;
  font-size: 11px;
  color: #ccccaa;
  line-height: 1.5;
}

.hint-icon {
  margin-right: 4px;
}
</style>
