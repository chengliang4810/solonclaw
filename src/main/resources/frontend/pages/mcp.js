const API_BASE = 'http://localhost:12345/api';

async function init() {
    await loadServers();
    setInterval(loadServers, 10000);
}

async function loadServers() {
    try {
        const response = await RequestUtil.get('/mcp/servers', { showError: false });
        const servers = response.data?.servers || [];

        if (servers.length === 0) {
            document.getElementById('mcpContent').innerHTML = `<div class="text-center text-gray-500 p-4">暂无 MCP 服务器</div>`;
            return;
        }

        document.getElementById('mcpContent').innerHTML = servers.map(server => `
            <div class="bg-gray-50 rounded-lg p-4 mb-3 card-hover">
                <div class="flex justify-between items-start">
                    <div class="flex-1">
                        <div class="flex items-center space-x-2">
                            <h3 class="font-medium text-gray-800">${server.name}</h3>
                            <span class="px-2 py-0.5 text-xs rounded-full ${server.running ? 'bg-green-100 text-green-600' : 'bg-gray-100 text-gray-600'}">
                                ${server.running ? '运行中' : '已停止'}
                            </span>
                            ${server.disabled ? '<span class="px-2 py-0.5 text-xs rounded-full bg-red-100 text-red-600">已禁用</span>' : ''}
                        </div>
                        <p class="text-sm text-gray-600 mt-1">命令: ${server.command} ${server.args?.join(' ') || ''}</p>
                    </div>
                    <div class="flex space-x-2 ml-4">
                        ${server.running
                            ? `<button onclick="stopServer('${server.name}')" class="text-sm px-3 py-1 bg-red-500 hover:bg-red-600 text-white rounded-lg">停止</button>`
                            : `<button onclick="startServer('${server.name}')" class="text-sm px-3 py-1 bg-green-500 hover:bg-green-600 text-white rounded-lg">启动</button>`
                        }
                        <button onclick="deleteServer('${server.name}')" class="text-sm px-3 py-1 bg-gray-500 hover:bg-gray-600 text-white rounded-lg">删除</button>
                    </div>
                </div>
            </div>
        `).join('');
    } catch (e) {
        document.getElementById('mcpContent').innerHTML = `<div class="text-center text-red-500 p-4">加载失败: ${e.message}</div>`;
    }
}

function openAddServerModal() {
    document.getElementById('addServerModal').classList.remove('hidden');
}

function closeAddServerModal() {
    document.getElementById('addServerModal').classList.add('hidden');
    document.getElementById('addServerForm').reset();
}

async function addServer(event) {
    event.preventDefault();

    const name = document.getElementById('serverName').value;
    const command = document.getElementById('serverCommand').value;
    const args = document.getElementById('serverArgs').value.split(' ').filter(a => a);

    try {
        await RequestUtil.post('/mcp/servers', { name, command, args });
        closeAddServerModal();
        loadServers();
    } catch (e) {
        alert('添加失败: ' + e.message);
    }
}

async function startServer(name) {
    try {
        await RequestUtil.post(`/mcp/servers/${name}/start`);
        loadServers();
    } catch (e) {
        alert('启动失败: ' + e.message);
    }
}

async function stopServer(name) {
    try {
        await RequestUtil.post(`/mcp/servers/${name}/stop`);
        loadServers();
    } catch (e) {
        alert('停止失败: ' + e.message);
    }
}

async function deleteServer(name) {
    if (!confirm(`确定要删除服务器 "${name}" 吗？`)) {
        return;
    }

    try {
        await RequestUtil.delete(`/mcp/servers/${name}`);
        loadServers();
    } catch (e) {
        alert('删除失败: ' + e.message);
    }
}

async function startAllServers() {
    try {
        await RequestUtil.post('/mcp/servers/start-all');
        loadServers();
    } catch (e) {
        alert('启动失败: ' + e.message);
    }
}

async function stopAllServers() {
    if (!confirm('确定要停止所有服务器吗？')) {
        return;
    }

    try {
        await RequestUtil.post('/mcp/servers/stop-all');
        loadServers();
    } catch (e) {
        alert('停止失败: ' + e.message);
    }
}

document.addEventListener('DOMContentLoaded', init);
