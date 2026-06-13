class WebSocketService {
  constructor() {
    this.ws = null
    this.reconnectTimer = null
    this.listeners = new Set()
    this.isConnecting = false
  }

  connect(url = `ws://${window.location.host}/ws/shuttles`) {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    this.isConnecting = true
    this.ws = new WebSocket(url)

    this.ws.onopen = () => {
      this.isConnecting = false
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer)
        this.reconnectTimer = null
      }
    }

    this.ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        this.listeners.forEach(cb => cb(data))
      } catch (e) {
        console.error('WebSocket parse error:', e)
      }
    }

    this.ws.onclose = () => {
      this.isConnecting = false
      this.scheduleReconnect(url)
    }

    this.ws.onerror = () => {
      this.isConnecting = false
    }
  }

  scheduleReconnect(url) {
    if (this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect(url)
    }, 3000)
  }

  subscribe(callback) {
    this.listeners.add(callback)
    return () => this.listeners.delete(callback)
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.listeners.clear()
  }
}

export const wsService = new WebSocketService()
