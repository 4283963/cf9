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

export async function getTrajectory(shuttleId, startTime, endTime) {
  const { data } = await api.get(`/trajectory/${shuttleId}`, {
    params: { startTime, endTime }
  })
  return data
}

export async function getTrajectoryLast(shuttleId, minutes = 10) {
  const { data } = await api.get(`/trajectory/${shuttleId}/last`, {
    params: { minutes }
  })
  return data
}

export async function getWarehouseConfig() {
  const { data } = await api.get('/warehouse/config')
  return data
}
