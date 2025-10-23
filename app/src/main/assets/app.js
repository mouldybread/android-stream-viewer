let cameras = [];
let serverUrl = '';
let logsVisible = false;
let logsInterval = null;
let logsPaused = false;
let clearClickCount = 0;
let clearClickTimeout = null;
let autoScrollLogs = true;
let webrtcSupported = true;
let savedLogHashes = new Set();
let lastLogContent = '';

// Detect WebRTC support
(function detectWebRTC() {
    webrtcSupported = !!(window.RTCPeerConnection || window.webkitRTCPeerConnection || window.mozRTCPeerConnection);
    console.log('WebRTC supported:', webrtcSupported);
})();

// Persistent logging
function saveLogToStorage(log) {
    try {
        const hash = log.substring(0, 60);
        if (savedLogHashes.has(hash)) return;

        savedLogHashes.add(hash);

        let persistentLogs = JSON.parse(localStorage.getItem('streamViewerLogs') || '[]');
        persistentLogs.push({
            timestamp: new Date().toISOString(),
            message: log
        });
        if (persistentLogs.length > 500) {
            persistentLogs = persistentLogs.slice(-500);
        }
        localStorage.setItem('streamViewerLogs', JSON.stringify(persistentLogs));
    } catch (e) {
        console.error('Failed to save log:', e);
    }
}

function loadPersistedLogs() {
    try {
        const persistentLogs = JSON.parse(localStorage.getItem('streamViewerLogs') || '[]');
        if (persistentLogs.length > 0) {
            const logsText = persistentLogs.map(l => `${l.timestamp} ${l.message}`).join('\n');
            return '\n=== PERSISTED LOGS ===\n' + logsText + '\n=== END PERSISTED LOGS ===\n\n';
        }
    } catch (e) {
        console.error('Failed to load persisted logs:', e);
    }
    return '';
}

// go2rtc URL persistence functions
function loadSavedGo2rtcUrl() {
    const savedUrl = localStorage.getItem('go2rtcServerUrl');
    const urlInput = document.getElementById('server-url');
    const removeBtn = document.getElementById('removeGo2rtcUrlBtn');

    if (savedUrl) {
        serverUrl = savedUrl;
        urlInput.value = savedUrl;
        urlInput.disabled = true;
        removeBtn.style.display = 'inline-block';

        // Sync to Android SharedPreferences
        fetch('/api/save-server-url', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: savedUrl })
        }).catch(err => console.error('Failed to sync server URL to Android:', err));
    } else {
        urlInput.disabled = false;
        removeBtn.style.display = 'none';
    }
}

function saveGo2rtcUrl(url) {
    localStorage.setItem('go2rtcServerUrl', url);
    serverUrl = url;

    const urlInput = document.getElementById('server-url');
    const removeBtn = document.getElementById('removeGo2rtcUrlBtn');

    urlInput.value = url;
    urlInput.disabled = true;
    removeBtn.style.display = 'inline-block';
}

function removeGo2rtcUrl() {
    localStorage.removeItem('go2rtcServerUrl');
    serverUrl = '';

    const urlInput = document.getElementById('server-url');
    const removeBtn = document.getElementById('removeGo2rtcUrlBtn');

    urlInput.value = '';
    urlInput.disabled = false;
    removeBtn.style.display = 'none';

    updateStatus('✓ go2rtc server URL removed', 'success');
}

async function loadCameras() {
    try {
        const response = await fetch('/api/cameras');
        if (!response.ok) throw new Error('Failed to load cameras');
        cameras = await response.json();
        renderCameras();

        // Load saved go2rtc server URL
        loadSavedGo2rtcUrl();

        updateStatus('✓ Cameras loaded', 'success');
    } catch (error) {
        console.error('Error loading cameras:', error);
        updateStatus('✗ Failed to load cameras', 'error');
    }
}

function renderCameras() {
    const list = document.getElementById('camera-list');
    const countBadge = document.getElementById('camera-count');
    countBadge.textContent = cameras.length;

    if (cameras.length === 0) {
        list.innerHTML = '<li class="empty-state"><div class="empty-state-icon">📹</div><div>No cameras configured</div><div style="font-size: 12px; margin-top: 10px;">Use "Discover Cameras" or "Add Camera" to get started</div></li>';
        return;
    }

    cameras.sort((a, b) => a.order - b.order);
    list.innerHTML = cameras.map(cam => `
        <li class="camera-item ${!cam.enabled ? 'disabled' : ''}" draggable="true" data-id="${cam.id}">
            <div class="camera-info">
                <h3>${escapeHtml(cam.name)}</h3>
                <p>Stream: ${escapeHtml(cam.streamName)} | ${cam.enabled ? '✓ Enabled' : '✗ Disabled'}</p>
            </div>
            <div class="camera-controls">
                ${renderProtocolSelector(cam)}
                <div class="camera-actions">
                    <button onclick="testCamera('${cam.id}')" class="success">▶️</button>
                    <button onclick="toggleCamera('${cam.id}')" class="secondary">${cam.enabled ? '⏸️' : '▶️'}</button>
                    <button onclick="deleteCamera('${cam.id}')" class="danger">🗑️</button>
                </div>
            </div>
        </li>
    `).join('');
    setupDragAndDrop();
}

function renderProtocolSelector(cam) {
    const protocols = webrtcSupported ? ['mse', 'webrtc'] : ['mse'];
    const labels = { 'webrtc': 'WebRTC', 'mse': 'MSE' };
    return `<div class="protocol-selector">${protocols.map(p => `<button class="${cam.protocol === p ? 'active' : ''}" onclick="changeProtocol('${cam.id}', '${p}')">${labels[p]}</button>`).join('')}</div>`;
}

async function changeProtocol(cameraId, protocol) {
    const camera = cameras.find(c => c.id === cameraId);
    if (camera) {
        camera.protocol = protocol;
        await saveCameras();
        renderCameras();
        updateStatus(`✓ ${camera.name}: ${protocol.toUpperCase()}`, 'success');
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function setupDragAndDrop() {
    const items = document.querySelectorAll('.camera-item');
    let draggedItem = null;
    items.forEach(item => {
        item.addEventListener('dragstart', function(e) { draggedItem = this; this.classList.add('dragging'); e.dataTransfer.effectAllowed = 'move'; });
        item.addEventListener('dragend', function() { this.classList.remove('dragging'); });
        item.addEventListener('dragover', function(e) { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; });
        item.addEventListener('drop', function(e) {
            e.preventDefault();
            if (draggedItem !== this) reorderCameras(draggedItem.dataset.id, this.dataset.id);
        });
    });
}

async function reorderCameras(draggedId, droppedId) {
    const draggedIdx = cameras.findIndex(c => c.id === draggedId);
    const droppedIdx = cameras.findIndex(c => c.id === droppedId);
    const [removed] = cameras.splice(draggedIdx, 1);
    cameras.splice(droppedIdx, 0, removed);
    cameras.forEach((cam, idx) => cam.order = idx);
    await saveCameras();
    renderCameras();
    updateStatus('✓ Order updated', 'success');
}

async function saveCameras() {
    try {
        const response = await fetch('/api/cameras', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(cameras)
        });
        if (!response.ok) throw new Error('Save failed');
    } catch (error) {
        console.error('Error saving cameras:', error);
        updateStatus('✗ Save failed', 'error');
    }
}

async function discoverCameras() {
    const url = document.getElementById('server-url').value.trim();
    if (!url) {
        updateStatus('⚠️ Please enter server URL', 'error');
        document.getElementById('discover-status').textContent = 'Please enter server URL';
        return;
    }

    serverUrl = url;
    document.getElementById('discover-status').textContent = '🔍 Discovering...';
    updateStatus('🔍 Discovering...', '');

    try {
        const response = await fetch('/api/discover', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ serverUrl: url })
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
            throw new Error(errorData.error || 'Discovery failed');
        }

        const streams = await response.json();
        const streamNames = Object.keys(streams);

        if (streamNames.length === 0) {
            document.getElementById('discover-status').textContent = '⚠️ No streams found';
            updateStatus('⚠️ No streams found', 'error');
            return;
        }

        let added = 0;
        for (const streamName of streamNames) {
            if (!cameras.find(c => c.streamName === streamName)) {
                cameras.push({
                    id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
                    name: streamName,
                    streamName: streamName,
                    enabled: true,
                    protocol: 'mse',
                    order: cameras.length
                });
                added++;
            }
        }

        await saveCameras();
        renderCameras();

        // Save the go2rtc URL after successful discovery
        saveGo2rtcUrl(url);

        document.getElementById('discover-status').textContent = `✓ Found ${streamNames.length} streams, added ${added} new`;
        updateStatus(`✓ Added ${added} cameras`, 'success');
        // Save server URL to AndroidWebServer
        fetch('/api/save-server-url', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: url })
        }).catch(err => console.error('Failed to save server URL:', err));
    } catch (error) {
        console.error('Discovery error:', error);
        document.getElementById('discover-status').textContent = '✗ Failed: ' + error.message;
        updateStatus('✗ Discovery failed', 'error');
    }
}

function showAddCamera() {
    document.getElementById('add-camera-modal').style.display = 'block';
    document.getElementById('new-camera-name').focus();
}

function hideAddCamera() {
    document.getElementById('add-camera-modal').style.display = 'none';
    document.getElementById('new-camera-name').value = '';
    document.getElementById('new-camera-stream').value = '';
    document.getElementById('new-camera-protocol').value = 'mse';
}

async function saveNewCamera() {
    const name = document.getElementById('new-camera-name').value.trim();
    const stream = document.getElementById('new-camera-stream').value.trim();
    const protocol = document.getElementById('new-camera-protocol').value;

    if (!name || !stream) {
        alert('⚠️ Please fill in all fields');
        return;
    }

    if (cameras.find(c => c.streamName === stream)) {
        if (!confirm('Camera with this stream exists. Add anyway?')) return;
    }

    cameras.push({
        id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
        name: name,
        streamName: stream,
        enabled: true,
        protocol: protocol,
        order: cameras.length
    });

    await saveCameras();
    renderCameras();
    hideAddCamera();
    updateStatus(`✓ Added: ${name}`, 'success');
}

async function testCamera(id) {
    const camera = cameras.find(c => c.id === id);
    if (!camera) return;

    let url = serverUrl;
    if (!url) {
        url = document.getElementById('server-url').value.trim();
        if (!url) {
            alert('⚠️ Please enter go2rtc server URL first');
            return;
        }
        serverUrl = url;
        saveGo2rtcUrl(url);
    }

    try {
        const response = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                go2rtcUrl: url,
                streamName: camera.streamName,
                protocol: camera.protocol
            })
        });

        if (!response.ok) throw new Error('Test failed');
        updateStatus(`▶️ Testing: ${camera.name}`, 'success');
    } catch (error) {
        alert('✗ Test failed: ' + error.message);
        updateStatus('✗ Test failed', 'error');
    }
}

async function toggleCamera(id) {
    const camera = cameras.find(c => c.id === id);
    if (camera) {
        camera.enabled = !camera.enabled;
        await saveCameras();
        renderCameras();
        updateStatus(`${camera.enabled ? '✓ Enabled' : '⏸️ Disabled'}: ${camera.name}`, 'success');
    }
}

async function deleteCamera(id) {
    const camera = cameras.find(c => c.id === id);
    if (!camera) return;
    if (!confirm(`Delete "${camera.name}"?`)) return;
    cameras = cameras.filter(c => c.id !== id);
    await saveCameras();
    renderCameras();
    updateStatus(`✓ Deleted: ${camera.name}`, 'success');
}

async function startTour() {
    const duration = parseInt(document.getElementById('tour-duration').value);
    const enabledCameras = cameras.filter(c => c.enabled);

    if (enabledCameras.length === 0) {
        alert('⚠️ No enabled cameras');
        return;
    }

    if (!serverUrl) {
        serverUrl = document.getElementById('server-url').value.trim();
        if (!serverUrl) {
            alert('⚠️ Please enter go2rtc server URL first');
            return;
        }
        saveGo2rtcUrl(serverUrl);
    }

    if (duration < 5 || duration > 600) {
        alert('⚠️ Duration must be 5-600 seconds');
        return;
    }

    try {
        const response = await fetch('/api/tour/start', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ duration: duration })
        });

        if (!response.ok) throw new Error('Tour start failed');
        updateStatus(`▶️ Tour: ${enabledCameras.length} cams, ${duration}s each`, 'success');
    } catch (error) {
        alert('✗ Tour failed: ' + error.message);
        updateStatus('✗ Tour failed', 'error');
    }
}

async function stopTour() {
    try {
        const response = await fetch('/api/tour/stop', { method: 'POST' });
        if (!response.ok) throw new Error('Stop failed');
        updateStatus('⏹️ Tour stopped', 'success');
    } catch (error) {
        alert('✗ Stop failed: ' + error.message);
        updateStatus('✗ Stop failed', 'error');
    }
}

// Burn-in protection functions
async function loadBurnInStatus() {
    try {
        const response = await fetch('/api/burn-in/status');
        if (!response.ok) throw new Error('Failed to load burn-in status');

        const status = await response.json();
        document.getElementById('burn-in-toggle').checked = status.enabled;
        updateBurnInStatus(status.enabled);
    } catch (error) {
        console.error('Error loading burn-in status:', error);
    }
}

async function toggleBurnInProtection() {
    const enabled = document.getElementById('burn-in-toggle').checked;

    try {
        const response = await fetch('/api/burn-in/toggle', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: enabled })
        });

        if (!response.ok) throw new Error('Failed to toggle burn-in protection');

        const result = await response.json();
        updateBurnInStatus(result.enabled);
        updateStatus(`🛡️ Burn-in protection ${result.enabled ? 'enabled' : 'disabled'}`, 'success');
    } catch (error) {
        console.error('Error toggling burn-in protection:', error);
        updateStatus('✗ Failed to toggle burn-in protection', 'error');
        // Revert checkbox
        document.getElementById('burn-in-toggle').checked = !enabled;
    }
}

function updateBurnInStatus(enabled) {
    const statusDiv = document.getElementById('burn-in-status');
    if (enabled) {
        statusDiv.textContent = '✓ Protection active: Screen will blank for 1 minute every 2 hours';
        statusDiv.style.color = '#28a745';
    } else {
        statusDiv.textContent = '⚠️ Protection disabled';
        statusDiv.style.color = '#ffc107';
    }
}

function toggleLogs() {
    logsVisible = !logsVisible;
    const logsDiv = document.getElementById('logs');
    const logControls = document.getElementById('log-controls');

    if (logsVisible) {
        logsDiv.classList.add('visible');
        logControls.style.display = 'flex';
        const persistedLogs = loadPersistedLogs();
        document.getElementById('logs-content').textContent = persistedLogs + 'Loading...';
        fetchLogs();
        if (!logsPaused) logsInterval = setInterval(fetchLogs, 500);
    } else {
        logsDiv.classList.remove('visible');
        logControls.style.display = 'none';
        if (logsInterval) {
            clearInterval(logsInterval);
            logsInterval = null;
        }
    }
}

function toggleLogPause() {
    logsPaused = !logsPaused;
    const statusIndicator = document.getElementById('log-status');
    const logsDiv = document.getElementById('logs');

    if (logsPaused) {
        if (logsInterval) {
            clearInterval(logsInterval);
            logsInterval = null;
        }
        statusIndicator.textContent = '⏸ Paused';
        statusIndicator.classList.add('paused');
        logsDiv.classList.add('paused');
    } else {
        logsInterval = setInterval(fetchLogs, 500);
        statusIndicator.textContent = '● Live';
        statusIndicator.classList.remove('paused');
        logsDiv.classList.remove('paused');
    }
}

async function fetchLogs() {
    if (logsPaused) return;
    try {
        const response = await fetch('/api/logs');
        if (!response.ok) throw new Error('Log fetch failed');
        const logs = await response.text();
        if (logs === lastLogContent) return;
        lastLogContent = logs;
        const logsContent = document.getElementById('logs-content');
        const logsDiv = document.getElementById('logs');
        const wasAtBottom = logsDiv.scrollHeight - logsDiv.scrollTop <= logsDiv.clientHeight + 50;
        logsContent.textContent = logs || 'No logs yet...';
        if (logs) {
            const logLines = logs.split('\n').filter(line => line.trim());
            const uniqueLines = [...new Set(logLines.slice(-5))];
            uniqueLines.forEach(log => {
                if (log.trim() && !log.includes('Server started') && !log.includes('Server initialized')) {
                    saveLogToStorage(log);
                }
            });
        }
        if (wasAtBottom && autoScrollLogs) logsDiv.scrollTop = logsDiv.scrollHeight;
    } catch (error) {
        document.getElementById('logs-content').textContent = '✗ Error: ' + error.message;
    }
}

async function copyLogs() {
    try {
        const logsText = document.getElementById('logs-content').textContent;
        if (navigator.clipboard && navigator.clipboard.writeText) {
            await navigator.clipboard.writeText(logsText);
            updateStatus('✓ Logs copied!', 'success');
        } else {
            const textarea = document.createElement('textarea');
            textarea.value = logsText;
            textarea.style.position = 'fixed';
            textarea.style.opacity = '0';
            document.body.appendChild(textarea);
            textarea.select();
            const success = document.execCommand('copy');
            document.body.removeChild(textarea);
            if (success) {
                updateStatus('✓ Logs copied!', 'success');
            } else {
                throw new Error('Copy command failed');
            }
        }
    } catch (error) {
        alert('✗ Copy failed. Use Download instead.');
        updateStatus('✗ Copy failed', 'error');
    }
}

async function downloadLogs() {
    try {
        const logsText = document.getElementById('logs-content').textContent;
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
        const filename = `logs-${timestamp}.txt`;
        const blob = new Blob([logsText], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);
        updateStatus('✓ Downloaded', 'success');
    } catch (error) {
        alert('✗ Download failed: ' + error.message);
        updateStatus('✗ Download failed', 'error');
    }
}

async function clearAllCameras() {
    clearClickCount++;
    if (clearClickCount === 1) {
        const buttons = document.querySelectorAll('button');
        const btn = Array.from(buttons).find(b => b.textContent.includes('Clear All'));
        if (btn) {
            btn.textContent = '⚠️ Click Again';
            btn.style.background = '#ff4444';
        }
        clearClickTimeout = setTimeout(() => {
            clearClickCount = 0;
            if (btn) {
                btn.textContent = '🗑️ Clear All';
                btn.style.background = '';
            }
        }, 3000);
    } else if (clearClickCount === 2) {
        clearTimeout(clearClickTimeout);
        cameras = [];
        await saveCameras();
        renderCameras();
        clearClickCount = 0;
        const buttons = document.querySelectorAll('button');
        const btn = Array.from(buttons).find(b => b.textContent.includes('Click Again'));
        if (btn) {
            btn.textContent = '🗑️ Clear All';
            btn.style.background = '';
        }
        updateStatus('✓ Cleared', 'success');
    }
}

function updateStatus(message, type = '') {
    const statusDiv = document.getElementById('status');
    statusDiv.textContent = message;
    statusDiv.className = type;
    if (type === 'success') {
        setTimeout(() => {
            if (statusDiv.textContent === message) {
                statusDiv.textContent = 'Ready - Waiting for commands';
                statusDiv.className = '';
            }
        }, 5000);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('new-camera-name').addEventListener('keypress', (e) => { if (e.key === 'Enter') saveNewCamera(); });
    document.getElementById('new-camera-stream').addEventListener('keypress', (e) => { if (e.key === 'Enter') saveNewCamera(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideAddCamera(); });
    document.getElementById('logs').addEventListener('scroll', function() {
        const isAtBottom = this.scrollHeight - this.scrollTop <= this.clientHeight + 50;
        autoScrollLogs = isAtBottom;
    });
    document.getElementById('logs').addEventListener('mouseup', function() {
        const selection = window.getSelection();
        if (selection.toString().length > 0 && !logsPaused) toggleLogPause();
    });
});

// Initialize
loadCameras();
loadBurnInStatus();
