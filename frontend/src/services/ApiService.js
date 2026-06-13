import axios from 'axios'

const api = axios.create({
  baseURL: '/api/shuttles',
  timeout: 10000
})

export async function getAllShuttleIds() {
  const { data } = await api.get('/ids')
  return data
}

export async function getAllCurrentStatus() {
  const { data } = await api.get('/status')
  return data
}

export async function getTrajectory(shuttleId, startTime, endTime, limit = 5000) {
  const { data } = await api.get(`/trajectory/${shuttleId}`, {
    params: { startTime, endTime, limit }
  })
  return data
}

export async function getTrajectoryLast(shuttleId, minutes = 10) {
  const safeMinutes = Math.max(1, Math.min(minutes, 120))
  const { data } = await api.get(`/trajectory/${shuttleId}/last`, {
    params: { minutes: safeMinutes }
  })
  return data
}

export async function getWarehouseConfig() {
  const { data } = await api.get('/warehouse/config')
  return data
}

export async function getCurrentLoadHistory(shuttleId, minutes = 10, limit = 3000) {
  const safeMinutes = Math.max(1, Math.min(minutes, 120))
  const { data } = await api.get(`/${shuttleId}/current-history`, {
    params: { minutes: safeMinutes, limit }
  })
  return data
}
