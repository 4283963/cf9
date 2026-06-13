<template>
  <div class="app-container">
    <div class="scene-container">
      <Warehouse3D ref="warehouse3dRef" />
    </div>

    <div class="control-panel" :class="{ collapsed: panelCollapsed }">
      <button class="panel-toggle" @click="panelCollapsed = !panelCollapsed">
        {{ panelCollapsed ? '◀' : '▶' }}
      </button>

      <div v-if="!panelCollapsed" class="panel-content">
        <div class="panel-header">
          <h1>穿梭车三维孪生</h1>
          <div class="connection-status" :class="wsConnected ? 'connected' : 'disconnected'">
            {{ wsConnected ? '● 已连接' : '○ 断开' }}
          </div>
        </div>

        <div class="stats-bar">
          <div class="stat-item">
            <span class="stat-value">{{ shuttleList.length }}</span>
            <span class="stat-label">总车数</span>
          </div>
          <div class="stat-item stat-loaded">
            <span class="stat-value">{{ loadedCount }}</span>
            <span class="stat-label">载货中</span>
          </div>
          <div class="stat-item stat-empty">
            <span class="stat-value">{{ shuttleList.length - loadedCount }}</span>
            <span class="stat-label">空载</span>
          </div>
          <div class="stat-item stat-charging">
            <span class="stat-value">{{ chargingCount }}</span>
            <span class="stat-label">充电</span>
          </div>
        </div>

        <div class="section">
          <h3>穿梭车列表</h3>
          <input
            v-model="searchText"
            class="search-input"
            placeholder="搜索车辆ID..."
          />
          <div class="shuttle-list">
            <div
              v-for="shuttle in filteredShuttles"
              :key="shuttle.shuttleId"
              class="shuttle-item"
              :class="{ active: selectedShuttleId === shuttle.shuttleId, loaded: shuttle.hasLoad }"
              @click="selectShuttle(shuttle)"
            >
              <div class="shuttle-id">
                <span class="status-dot" :class="getStatusClass(shuttle)"></span>
                {{ shuttle.shuttleId }}
              </div>
              <div class="shuttle-meta">
                <span class="battery" :class="{ low: shuttle.batteryLevel < 20 }">
                  {{ Math.round(shuttle.batteryLevel) }}%
                </span>
                <span class="speed">{{ shuttle.speed?.toFixed(1) }}m/s</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="selectedShuttle" class="section">
          <h3>车辆详情</h3>
          <div class="detail-grid">
            <div class="detail-row">
              <span class="detail-label">ID</span>
              <span class="detail-value">{{ selectedShuttle.shuttleId }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">层级</span>
              <span class="detail-value">L{{ selectedShuttle.level }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">巷道</span>
              <span class="detail-value">A{{ selectedShuttle.aisle }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">位置</span>
              <span class="detail-value">
                ({{ selectedShuttle.x?.toFixed(1) }}, {{ selectedShuttle.y?.toFixed(1) }}, {{ selectedShuttle.z?.toFixed(1) }})
              </span>
            </div>
            <div class="detail-row">
              <span class="detail-label">电量</span>
              <span class="detail-value">
                <div class="battery-bar">
                  <div class="battery-fill" :style="{ width: selectedShuttle.batteryLevel + '%' }"
                       :class="{ low: selectedShuttle.batteryLevel < 20, mid: selectedShuttle.batteryLevel < 50 && selectedShuttle.batteryLevel >= 20 }">
                  </div>
                </div>
                {{ Math.round(selectedShuttle.batteryLevel) }}%
              </span>
            </div>
            <div class="detail-row">
              <span class="detail-label">状态</span>
              <span class="detail-value" :class="'status-' + selectedShuttle.status?.toLowerCase()">
                {{ statusText(selectedShuttle.status) }}
              </span>
            </div>
            <div class="detail-row">
              <span class="detail-label">载货</span>
              <span class="detail-value" :class="selectedShuttle.hasLoad ? 'loaded-text' : 'empty-text'">
                {{ selectedShuttle.hasLoad ? '是' : '否' }}
              </span>
            </div>
          </div>
          <button class="focus-btn" @click="focusShuttle">聚焦此车</button>
        </div>

        <div class="section">
          <h3>轨迹查询</h3>
          <select v-model="trajectoryShuttleId" class="select-input">
            <option value="">选择车辆</option>
            <option v-for="id in shuttleIds" :key="id" :value="id">{{ id }}</option>
          </select>
          <div class="time-range">
            <label>最近</label>
            <select v-model="trajectoryMinutes" class="select-input small">
              <option :value="5">5分钟</option>
              <option :value="10">10分钟</option>
              <option :value="30">30分钟</option>
              <option :value="60">60分钟</option>
            </select>
          </div>
          <div class="btn-group">
            <button class="action-btn" @click="queryTrajectory" :disabled="!trajectoryShuttleId || trajectoryLoading">
              {{ trajectoryLoading ? '查询中...' : '查询轨迹' }}
            </button>
            <button class="action-btn secondary" @click="clearTrajectory">清除轨迹</button>
          </div>
          <div v-if="trajectoryCount > 0" class="trajectory-info">
            已加载 {{ trajectoryCount }} 个轨迹点
          </div>
        </div>

        <div class="section">
          <h3>图例</h3>
          <div class="legend-grid">
            <div class="legend-item"><span class="legend-dot" style="background:#00ff88"></span>空载移动</div>
            <div class="legend-item"><span class="legend-dot" style="background:#ffcc00"></span>载货移动</div>
            <div class="legend-item"><span class="legend-dot" style="background:#44aaff"></span>取货中</div>
            <div class="legend-item"><span class="legend-dot" style="background:#ff8844"></span>放货中</div>
            <div class="legend-item"><span class="legend-dot" style="background:#ff4444"></span>充电中</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import Warehouse3D from './components/Warehouse3D.vue'
import { wsService } from './services/WebSocketService.js'
import { getAllShuttleIds, getTrajectoryLast } from './services/ApiService.js'

const warehouse3dRef = ref(null)
const panelCollapsed = ref(false)
const wsConnected = ref(false)
const shuttleList = ref([])
const shuttleIds = ref([])
const searchText = ref('')
const selectedShuttleId = ref(null)
const selectedShuttle = ref(null)
const trajectoryShuttleId = ref('')
const trajectoryMinutes = ref(10)
const trajectoryLoading = ref(false)
const trajectoryCount = ref(0)

const loadedCount = computed(() => shuttleList.value.filter(s => s.hasLoad).length)
const chargingCount = computed(() => shuttleList.value.filter(s => s.status === 'CHARGING').length)

const filteredShuttles = computed(() => {
  let list = shuttleList.value
  if (searchText.value) {
    const q = searchText.value.toLowerCase()
    list = list.filter(s => s.shuttleId.toLowerCase().includes(q))
  }
  return list.sort((a, b) => a.shuttleId.localeCompare(b.shuttleId))
})

function getStatusClass(shuttle) {
  if (shuttle.status === 'CHARGING') return 'charging'
  if (shuttle.status === 'PICKING') return 'picking'
  if (shuttle.status === 'DROPPING') return 'dropping'
  return shuttle.hasLoad ? 'loaded' : 'empty'
}

function statusText(status) {
  const map = { MOVING: '移动中', PICKING: '取货中', DROPPING: '放货中', CHARGING: '充电中' }
  return map[status] || status
}

function selectShuttle(shuttle) {
  selectedShuttleId.value = shuttle.shuttleId
  selectedShuttle.value = shuttle
}

function focusShuttle() {
  if (warehouse3dRef.value && selectedShuttleId.value) {
    warehouse3dRef.value.focusOnShuttle(selectedShuttleId.value)
  }
}

async function queryTrajectory() {
  if (!trajectoryShuttleId.value) return
  trajectoryLoading.value = true
  try {
    const points = await getTrajectoryLast(trajectoryShuttleId.value, trajectoryMinutes.value)
    if (warehouse3dRef.value) {
      warehouse3dRef.value.setTrajectoryPoints(points)
    }
    trajectoryCount.value = points.length
  } catch (e) {
    console.error('轨迹查询失败:', e)
  } finally {
    trajectoryLoading.value = false
  }
}

function clearTrajectory() {
  if (warehouse3dRef.value) {
    warehouse3dRef.value.clearTrajectoryPoints()
  }
  trajectoryCount.value = 0
}

let unsubscribe = null
let lastRenderUpdate = 0
const RENDER_THROTTLE_MS = 200
let pendingStatuses = null
let renderRafId = null

function scheduleRenderUpdate(statuses) {
  pendingStatuses = statuses
  if (renderRafId) return
  renderRafId = requestAnimationFrame(() => {
    renderRafId = null
    const now = performance.now()
    if (now - lastRenderUpdate >= RENDER_THROTTLE_MS && pendingStatuses && warehouse3dRef.value) {
      lastRenderUpdate = now
      warehouse3dRef.value.updateShuttles(pendingStatuses)
      pendingStatuses = null
    } else if (pendingStatuses) {
      scheduleRenderUpdate(pendingStatuses)
    }
  })
}

onMounted(async () => {
  try {
    shuttleIds.value = await getAllShuttleIds()
  } catch (e) {
    console.warn('获取车辆ID列表失败，等待WebSocket推送')
  }

  unsubscribe = wsService.subscribe((statuses) => {
    wsConnected.value = true
    shuttleList.value = statuses

    if (selectedShuttleId.value) {
      const updated = statuses.find(s => s.shuttleId === selectedShuttleId.value)
      if (updated) {
        selectedShuttle.value = updated
      }
    }

    scheduleRenderUpdate(statuses)

    if (shuttleIds.value.length === 0 && statuses.length > 0) {
      shuttleIds.value = statuses.map(s => s.shuttleId).sort()
    }
  })

  wsService.connect()
})

onBeforeUnmount(() => {
  if (renderRafId) {
    cancelAnimationFrame(renderRafId)
    renderRafId = null
  }
  if (unsubscribe) unsubscribe()
  wsService.disconnect()
})
</script>

<style>
@import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&display=swap');

* { margin: 0; padding: 0; box-sizing: border-box; }

.app-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  background: #0a0a1a;
  color: #e0e0e0;
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden;
}

.scene-container {
  flex: 1;
  height: 100%;
  position: relative;
}

.warehouse-3d {
  width: 100%;
  height: 100%;
}

.control-panel {
  width: 340px;
  height: 100%;
  background: linear-gradient(180deg, #0d0d24 0%, #0a0a1a 100%);
  border-left: 1px solid #1a1a3e;
  display: flex;
  position: relative;
  transition: width 0.3s ease;
  overflow: hidden;
}

.control-panel.collapsed {
  width: 36px;
}

.panel-toggle {
  position: absolute;
  top: 12px;
  left: 8px;
  z-index: 10;
  background: #1a1a3e;
  border: 1px solid #2a2a5e;
  color: #4488ff;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
}

.panel-content {
  flex: 1;
  padding: 16px;
  overflow-y: auto;
  overflow-x: hidden;
}

.panel-content::-webkit-scrollbar {
  width: 4px;
}

.panel-content::-webkit-scrollbar-track {
  background: transparent;
}

.panel-content::-webkit-scrollbar-thumb {
  background: #2a2a5e;
  border-radius: 2px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #1a1a3e;
}

.panel-header h1 {
  font-size: 14px;
  font-weight: 600;
  color: #4488ff;
  letter-spacing: 1px;
}

.connection-status {
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.connection-status.connected { color: #00ff88; }
.connection-status.disconnected { color: #ff4444; }

.stats-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.stat-item {
  flex: 1;
  background: #111130;
  border: 1px solid #1a1a3e;
  border-radius: 6px;
  padding: 8px 4px;
  text-align: center;
}

.stat-value {
  display: block;
  font-size: 18px;
  font-weight: 600;
  color: #4488ff;
}

.stat-loaded .stat-value { color: #ffcc00; }
.stat-empty .stat-value { color: #00ff88; }
.stat-charging .stat-value { color: #ff4444; }

.stat-label {
  display: block;
  font-size: 9px;
  color: #666688;
  margin-top: 2px;
}

.section {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #1a1a3e;
}

.section h3 {
  font-size: 11px;
  color: #6688bb;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 8px;
}

.search-input, .select-input {
  width: 100%;
  background: #111130;
  border: 1px solid #1a1a3e;
  color: #e0e0e0;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-family: inherit;
  margin-bottom: 8px;
  outline: none;
}

.search-input:focus, .select-input:focus {
  border-color: #4488ff;
}

.select-input.small {
  width: auto;
  display: inline-block;
}

.shuttle-list {
  max-height: 200px;
  overflow-y: auto;
  margin-bottom: 8px;
}

.shuttle-list::-webkit-scrollbar { width: 3px; }
.shuttle-list::-webkit-scrollbar-thumb { background: #2a2a5e; border-radius: 2px; }

.shuttle-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}

.shuttle-item:hover { background: #1a1a3e; }
.shuttle-item.active { background: #1a2a4e; border: 1px solid #4488ff; }

.shuttle-id {
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.status-dot.empty { background: #00ff88; box-shadow: 0 0 4px #00ff88; }
.status-dot.loaded { background: #ffcc00; box-shadow: 0 0 4px #ffcc00; }
.status-dot.charging { background: #ff4444; box-shadow: 0 0 4px #ff4444; }
.status-dot.picking { background: #44aaff; box-shadow: 0 0 4px #44aaff; }
.status-dot.dropping { background: #ff8844; box-shadow: 0 0 4px #ff8844; }

.shuttle-meta {
  font-size: 10px;
  color: #666688;
  display: flex;
  gap: 8px;
}

.battery.low { color: #ff4444; }

.detail-grid {
  margin-bottom: 8px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  border-bottom: 1px solid #111130;
  font-size: 11px;
}

.detail-label { color: #666688; }
.detail-value { color: #e0e0e0; }

.battery-bar {
  display: inline-block;
  width: 60px;
  height: 8px;
  background: #1a1a3e;
  border-radius: 4px;
  overflow: hidden;
  vertical-align: middle;
  margin-right: 6px;
}

.battery-fill {
  height: 100%;
  background: #00ff88;
  border-radius: 4px;
  transition: width 0.3s;
}

.battery-fill.low { background: #ff4444; }
.battery-fill.mid { background: #ffcc00; }

.status-moving { color: #4488ff; }
.status-picking { color: #44aaff; }
.status-dropping { color: #ff8844; }
.status-charging { color: #ff4444; }

.loaded-text { color: #ffcc00; }
.empty-text { color: #00ff88; }

.focus-btn {
  width: 100%;
  padding: 6px;
  background: #1a2a4e;
  border: 1px solid #4488ff;
  color: #4488ff;
  border-radius: 4px;
  cursor: pointer;
  font-family: inherit;
  font-size: 11px;
  transition: all 0.2s;
}

.focus-btn:hover { background: #4488ff; color: #fff; }

.time-range {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 11px;
  color: #666688;
}

.btn-group {
  display: flex;
  gap: 6px;
}

.action-btn {
  flex: 1;
  padding: 6px;
  background: #1a3a2e;
  border: 1px solid #00ff88;
  color: #00ff88;
  border-radius: 4px;
  cursor: pointer;
  font-family: inherit;
  font-size: 11px;
  transition: all 0.2s;
}

.action-btn:hover:not(:disabled) { background: #00ff88; color: #0a0a1a; }
.action-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.action-btn.secondary { background: #2a1a1a; border-color: #ff4444; color: #ff4444; }
.action-btn.secondary:hover { background: #ff4444; color: #fff; }

.trajectory-info {
  margin-top: 6px;
  font-size: 10px;
  color: #00ffcc;
}

.legend-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: #8888aa;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
</style>
