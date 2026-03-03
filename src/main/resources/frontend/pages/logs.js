const API_BASE = 'http://localhost:12345/api';

async function init() {
    await loadLogs();
}

async function loadLogs() {
    const level = document.getElementById('logLevel').value;
    const source = document.getElementById('logSource').value;
    const keyword = document.getElementById('logKeyword').value;

    const params = new URLSearchParams();
    if (level) params.append('levels', level);
    if (source) params.append('sources', source);
    if (keyword) params.append('keyword', keyword);
    params.append('page', '1');
    params.append('pageSize', '50');

    try {
        const logs = await RequestUtil.get(`/logs?${params.toString()}`, { showError: false });

        if (logs.length === 0) {
            document.getElementById('logsContent').innerHTML = `<div class="text-center text-gray-500 p-4">暂无日志</div>`;
            return;
        }

        document.getElementById('logsContent').innerHTML = logs.map(log => {
            const levelClass = {
                'INFO': 'text-blue-600',
                'DEBUG': 'text-gray-600',
                'ERROR': 'text-red-600',
                'WARN': 'text-yellow-600'
            }[log.level] || 'text-gray-600';

            return `
                <div class="font-mono text-sm border-b border-gray-200 py-2">
                    <div class="flex justify-between items-start">
                        <div class="flex-1">
                            <span class="${levelClass} font-medium">[${log.level}]</span>
                            <span class="text-gray-500">[${log.source}]</span>
                            <span class="text-gray-800">${log.message || ''}</span>
                        </div>
                        <div class="text-xs text-gray-400 ml-4">${new Date(log.timestamp).toLocaleString()}</div>
                    </div>
                    ${log.details ? `<div class="text-xs text-gray-500 mt-1 ml-4">${log.details}</div>` : ''}
                </div>
            `;
        }).join('');
    } catch (e) {
        document.getElementById('logsContent').innerHTML = `<div class="text-center text-red-500 p-4">加载失败: ${e.message}</div>`;
    }
}

function filterLogs() {
    loadLogs();
}

async function clearLogs() {
    if (!confirm('确定要清空所有日志吗？此操作不可恢复！')) {
        return;
    }

    try {
        await RequestUtil.delete('/logs');
        loadLogs();
    } catch (e) {
        alert('清空失败: ' + e.message);
    }
}

document.addEventListener('DOMContentLoaded', init);
