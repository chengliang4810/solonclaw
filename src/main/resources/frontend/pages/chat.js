/**
 * SolonClaw AI 对话 - 流式响应与 Markdown 渲染
 */

const API_BASE = '/api';
let sessionId = null;
let isStreaming = false;
let currentAssistantBubble = null;
let fullResponseContent = '';
let hasReceivedContent = false;
let thinkingContentBubble = null;
let hasFinishedThinking = false; // 思考是否已结束
let toolCallElements = []; // 存储工具调用元素，等待正确位置插入
let lastIsThinking = null; // 记录上一次的是否为思考状态
let thinkingRawContent = ''; // 保存思考内容的原始文本，避免 HTML 解析问题

// ==================== Markdown 配置 ====================

// 配置 marked 选项
marked.setOptions({
    gfm: true,
    breaks: true,
    headerIds: false,
    mangle: false,
    pedantic: false,
    highlight: function(code, lang) {
        if (lang && hljs.getLanguage(lang)) {
            try {
                return hljs.highlight(code, { language: lang }).value;
            } catch (e) {
                console.warn('代码高亮失败:', lang, e);
            }
        }
        return code;
    }
});

// 自定义 marked 渲染器
const renderer = new marked.Renderer();

// 自定义代码块渲染（带语言标签和复制按钮）
renderer.code = function(token) {
    const code = token.text || token.code || '';
    const lang = token.lang || 'text';
    const escapedCode = code.replace(/</g, '&lt;').replace(/>/g, '&gt;');

    return `
<div class="code-block-container">
    <div class="code-header">
        <span class="code-language">${lang}</span>
        <button class="copy-code-btn" onclick="copyCode(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
            </svg>
            复制
        </button>
    </div>
    <pre><code class="language-${lang}">${escapedCode}</code></pre>
</div>`;
};

// 自定义链接渲染（外部链接新窗口打开）
renderer.link = function(token) {
    const href = token.href;
    const title = token.title;
    const text = token.text;
    const target = href && href.startsWith('http') ? ' target="_blank" rel="noopener noreferrer"' : '';
    return `<a href="${href}"${target}${title ? ` title="${title}"` : ''}>${text}</a>`;
};

marked.use({ renderer });

// ==================== 初始化 ====================

document.addEventListener('DOMContentLoaded', () => {
    initEventListeners();
    autoResizeTextarea();
});

function initEventListeners() {
    const sendBtn = document.getElementById('sendButton');
    const messageInput = document.getElementById('messageInput');

    sendBtn.addEventListener('click', handleSendMessage);

    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSendMessage();
        }
    });

    messageInput.addEventListener('input', () => {
        autoResizeTextarea();
        updateCharCount();
    });
}

function autoResizeTextarea() {
    const input = document.getElementById('messageInput');
    input.style.height = 'auto';
    const newHeight = Math.min(input.scrollHeight, 150);
    input.style.height = newHeight + 'px';
}

function updateCharCount() {
    const input = document.getElementById('messageInput');
    const countEl = document.getElementById('charCount');
    const length = input.value.length;
    countEl.textContent = `${length} / 2000`;
    
    if (length > 1800) {
        countEl.style.color = '#ef4444';
    } else {
        countEl.style.color = '#6b7280';
    }
}

// ==================== 消息处理 ====================

async function handleSendMessage() {
    if (isStreaming) return;

    const input = document.getElementById('messageInput');
    const message = input.value.trim();

    if (!message) return;
    if (message.length > 2000) {
        showToast('消息长度不能超过 2000 字符', 'error');
        return;
    }

    // 隐藏欢迎界面
    const welcomeContainer = document.getElementById('welcomeContainer');
    if (welcomeContainer) {
        welcomeContainer.style.display = 'none';
    }

    // 显示用户消息
    appendUserMessage(message);

    // 清空输入框
    input.value = '';
    autoResizeTextarea();
    updateCharCount();

    // 显示思考中动画
    showThinkingIndicator();

    // 开始流式请求
    isStreaming = true;
    updateSendButtonState(true);
    fullResponseContent = '';
    hasReceivedContent = false;
    thinkingContentBubble = null;

    try {
        await fetchStreamResponse(message);
    } catch (error) {
        console.error('流式请求失败:', error);
        removeThinkingIndicator();
        appendErrorMessage(error.message);
        showToast('请求失败：' + error.message, 'error');
    } finally {
        isStreaming = false;
        updateSendButtonState(false);
        currentAssistantBubble = null;
    }
}

async function fetchStreamResponse(message) {
    const response = await fetch(`${API_BASE}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, sessionId })
    });

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // 解析 SSE 行
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
            const trimmedLine = line.trim();
            
            if (trimmedLine.startsWith('event:')) {
                // 处理事件类型行
                if (trimmedLine.startsWith('event: session')) {
                    // 下一行是 sessionId
                    continue;
                }
                continue;
            }

            if (trimmedLine.startsWith('data:')) {
                const data = trimmedLine.slice(5).trim();
                
                // 处理 sessionId（纯文本，非 JSON）
                if (!sessionId && data && !data.startsWith('{')) {
                    sessionId = data;
                    continue;
                }

                if (!data) continue;

                try {
                    const event = JSON.parse(data);
                    processStreamEvent(event);
                } catch (e) {
                    console.warn('解析 SSE 事件失败:', e, trimmedLine);
                }
            }
        }
    }
}

function processStreamEvent(event) {
    switch (event.type) {
        case 'START':
            // 开始处理，移除思考中动画
            removeThinkingIndicator();
            hasFinishedThinking = false;
            toolCallElements = []; // 重置工具调用列表
            lastIsThinking = null; // 重置思考状态
            thinkingRawContent = ''; // 重置思考原始内容
            break;

        case 'CONTENT':
            const content = event.content || '';

            // 如果内容为空，跳过处理
            if (!content) {
                break;
            }

            // 判断是否为思考内容：isThinking === true
            const isThinkingContent = event.isThinking === true;

            // 检测 isThinking 是否切换了（从思考变为答案，或从答案变为思考）
            const isThinkingChanged = lastIsThinking !== null && lastIsThinking !== isThinkingContent;

            if (isThinkingContent) {
                // 思考内容
                hasFinishedThinking = false;

                // 如果 isThinking 切换了，或者还没有思考气泡，创建新的
                if (isThinkingChanged || !thinkingContentBubble) {
                    // 先清空之前的思考气泡引用，创建一个新的
                    thinkingContentBubble = null;
                }
                appendThinkingContent(content);
            } else {
                // 正常回答内容 - 最终答案
                hasFinishedThinking = true;

                // 如果 isThinking 切换了，或者还没有答案气泡，创建新的
                if (isThinkingChanged || !currentAssistantBubble) {
                    currentAssistantBubble = null;
                }

                // 创建 AI 气泡
                if (!currentAssistantBubble) {
                    createAssistantBubble();
                }

                // 先将之前暂存的工具调用插入到思考之后、答案之前
                insertPendingToolCalls();

                fullResponseContent += content;
                // 实时渲染 Markdown
                renderAssistantContent(fullResponseContent, true);
            }

            // 更新上一次的状态
            lastIsThinking = isThinkingContent;

            // 自动滚动到底部
            scrollToBottom();
            break;

        case 'TOOL_CALL':
            // 工具调用 - 暂存起来，等最终答案到达时插入到正确位置
            const toolName = event.toolName || '未知工具';
            const toolArgs = event.toolArgs || {};
            const toolMessage = event.content || `正在调用工具：${toolName}`;

            // 如果已经有最终答案气泡，立即插入到答案之前
            if (currentAssistantBubble) {
                insertPendingToolCalls();
                appendToolCallContent(toolName, toolArgs, toolMessage);
            } else {
                // 否则暂存起来
                toolCallElements.push({ toolName, toolArgs, toolMessage });
            }
            scrollToBottom();
            break;

        case 'END':
        case 'done':
            // 流式结束，最终渲染
            if (currentAssistantBubble) {
                renderAssistantContent(fullResponseContent, false);
            }
            scrollToBottom();

            // 思考结束后，折叠思考过程
            if (hasFinishedThinking || thinkingContentBubble) {
                collapseThinkingContent();
            }
            break;

        case 'ERROR':
            throw new Error(event.error || event.content || '未知错误');
    }
}

// ==================== UI 操作 ====================

function appendUserMessage(content) {
    const messagesContainer = document.getElementById('chatMessages');

    const messageRow = document.createElement('div');
    messageRow.className = 'message-row user';

    const bubble = document.createElement('div');
    bubble.className = 'message-bubble user';

    // 用户消息不需要 Markdown 渲染，直接显示文本
    bubble.textContent = content;

    messageRow.appendChild(bubble);
    messagesContainer.appendChild(messageRow);
    scrollToBottom();
}

function appendThinkingContent(content) {
    if (!thinkingContentBubble) {
        // 创建思考内容气泡
        const messagesContainer = document.getElementById('chatMessages');
        const thinkingRow = document.createElement('div');
        thinkingRow.className = 'message-row assistant';

        thinkingRow.innerHTML = `
            <div class="thinking-content">
                <div class="thinking-content-label" onclick="toggleThinkingContent()">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="thinking-icon">
                        <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
                        <path d="M9 21a3 3 0 0 0 6 0"/>
                    </svg>
                    <span>思考过程</span>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="expand-icon">
                        <polyline points="6 9 12 15 18 9"></polyline>
                    </svg>
                </div>
                <div class="thinking-content-body"></div>
            </div>
        `;

        messagesContainer.appendChild(thinkingRow);
        thinkingContentBubble = thinkingRow.querySelector('.thinking-content-body');
        thinkingRawContent = ''; // 初始化原始内容
    }

    // 追加到原始内容（保留空格）
    thinkingRawContent += content;
    // 使用 Markdown 渲染
    thinkingContentBubble.innerHTML = marked.parse(thinkingRawContent);
}

/**
 * 切换思考过程的展开/折叠状态
 */
function toggleThinkingContent() {
    const thinkingContent = document.querySelector('.thinking-content');
    if (thinkingContent) {
        thinkingContent.classList.toggle('collapsed');
    }
}

/**
 * 添加工具调用内容
 */
function appendToolCallContent(toolName, toolArgs, toolMessage) {
    const messagesContainer = document.getElementById('chatMessages');
    const toolRow = document.createElement('div');
    toolRow.className = 'message-row assistant';

    // 格式化参数为 JSON 字符串
    let argsText = '';
    if (Object.keys(toolArgs).length > 0) {
        argsText = JSON.stringify(toolArgs, null, 2);
    }

    toolRow.innerHTML = `
        <div class="tool-call-content">
            <div class="tool-call-header">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="tool-icon">
                    <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
                </svg>
                <span class="tool-name">调用工具：${toolName}</span>
            </div>
            ${argsText ? `<pre class="tool-args">${escapeHtml(argsText)}</pre>` : ''}
        </div>
    `;

    messagesContainer.appendChild(toolRow);
    scrollToBottom();
}

/**
 * 折叠思考过程
 */
function collapseThinkingContent() {
    const thinkingContent = document.querySelector('.thinking-content');
    if (thinkingContent) {
        thinkingContent.classList.add('collapsed');
        const headerLabel = thinkingContent.querySelector('.thinking-content-label');
        if (headerLabel) {
            headerLabel.classList.add('clickable');
        }
    }
}

/**
 * 插入暂存的工具调用到正确位置（思考之后、答案之前）
 */
function insertPendingToolCalls() {
    if (toolCallElements.length === 0) return;

    const messagesContainer = document.getElementById('chatMessages');
    const thinkingRow = document.querySelector('.message-row.assistant .thinking-content')?.closest('.message-row');
    const answerRow = currentAssistantBubble?.closest('.message-row');

    // 获取思考过程行的下一个位置
    let insertPosition = thinkingRow ? thinkingRow.nextSibling : null;

    // 如果没有思考过程，就插入到答案之前
    if (!insertPosition && answerRow) {
        insertPosition = answerRow;
    }

    // 插入所有暂存的工具调用
    for (const tool of toolCallElements) {
        const toolRow = document.createElement('div');
        toolRow.className = 'message-row assistant';

        let argsText = '';
        if (Object.keys(tool.toolArgs).length > 0) {
            argsText = JSON.stringify(tool.toolArgs, null, 2);
        }

        toolRow.innerHTML = `
            <div class="tool-call-content">
                <div class="tool-call-header">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="tool-icon">
                        <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
                    </svg>
                    <span class="tool-name">调用工具：${tool.toolName}</span>
                </div>
                ${argsText ? `<pre class="tool-args">${escapeHtml(argsText)}</pre>` : ''}
            </div>
        `;

        if (insertPosition) {
            messagesContainer.insertBefore(toolRow, insertPosition);
            insertPosition = toolRow.nextSibling;
        } else {
            messagesContainer.appendChild(toolRow);
        }
    }

    // 清空暂存列表
    toolCallElements = [];
}

function showThinkingIndicator() {
    const messagesContainer = document.getElementById('chatMessages');
    
    const thinkingRow = document.createElement('div');
    thinkingRow.className = 'message-row assistant';
    thinkingRow.id = 'thinkingIndicator';
    
    thinkingRow.innerHTML = `
        <div class="thinking-indicator">
            <span>思考中</span>
            <div class="thinking-dots">
                <div class="thinking-dot"></div>
                <div class="thinking-dot"></div>
                <div class="thinking-dot"></div>
            </div>
        </div>
    `;
    
    messagesContainer.appendChild(thinkingRow);
    scrollToBottom();
}

function removeThinkingIndicator() {
    const thinkingIndicator = document.getElementById('thinkingIndicator');
    if (thinkingIndicator) {
        thinkingIndicator.remove();
    }
}

function createAssistantBubble() {
    if (currentAssistantBubble) return;

    const messagesContainer = document.getElementById('chatMessages');
    
    const messageRow = document.createElement('div');
    messageRow.className = 'message-row assistant';
    
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble assistant markdown-content';
    
    messageRow.appendChild(bubble);
    messagesContainer.appendChild(messageRow);
    
    currentAssistantBubble = bubble;
}

function renderAssistantContent(content, showCursor) {
    if (!currentAssistantBubble) return;

    try {
        const html = marked.parse(content);
        currentAssistantBubble.innerHTML = showCursor 
            ? html + '<span class="typing-cursor"></span>'
            : html;

        // 代码高亮
        currentAssistantBubble.querySelectorAll('pre code').forEach((block) => {
            hljs.highlightElement(block);
        });

        // 渲染数学公式（KaTeX）
        if (typeof katex !== 'undefined') {
            renderMathInBubble(currentAssistantBubble);
        }
    } catch (e) {
        console.error('Markdown 渲染失败:', e);
        currentAssistantBubble.textContent = content;
    }
}

function renderMathInBubble(element) {
    // 行内公式：$...$
    const inlineMathPattern = /\$([^\n$]+?)\$/g;
    // 块级公式：$$...$$
    const displayMathPattern = /\$\$([\s\S]+?)\$\$/g;

    // 处理块级公式
    element.innerHTML = element.innerHTML.replace(displayMathPattern, (match, formula) => {
        try {
            return katex.renderToString(formula.trim(), { displayMode: true });
        } catch (e) {
            return match;
        }
    });

    // 处理行内公式
    element.innerHTML = element.innerHTML.replace(inlineMathPattern, (match, formula) => {
        try {
            return katex.renderToString(formula.trim(), { displayMode: false });
        } catch (e) {
            return match;
        }
    });
}

function appendErrorMessage(errorMsg) {
    const messagesContainer = document.getElementById('chatMessages');
    
    const messageRow = document.createElement('div');
    messageRow.className = 'message-row assistant';
    
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble assistant';
    bubble.style.color = '#ef4444';
    bubble.textContent = `❌ 处理失败：${errorMsg}`;
    
    messageRow.appendChild(bubble);
    messagesContainer.appendChild(messageRow);
    scrollToBottom();
}

function scrollToBottom() {
    const messagesContainer = document.getElementById('chatMessages');
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

function updateSendButtonState(disabled) {
    const sendBtn = document.getElementById('sendButton');
    sendBtn.disabled = disabled;
}

// ==================== 工具函数 ====================

function useSuggestion(text) {
    if (isStreaming) return;
    
    const input = document.getElementById('messageInput');
    input.value = text;
    autoResizeTextarea();
    updateCharCount();
    input.focus();
}

function copyCode(button) {
    const codeBlock = button.closest('.code-block-container');
    const code = codeBlock.querySelector('code').textContent;
    
    navigator.clipboard.writeText(code).then(() => {
        // 显示复制成功提示
        const originalText = button.innerHTML;
        button.innerHTML = `
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="20 6 9 17 4 12"></polyline>
            </svg>
            已复制
        `;
        button.style.color = '#10b981';
        
        setTimeout(() => {
            button.innerHTML = originalText;
            button.style.color = '';
        }, 2000);
    }).catch(err => {
        console.error('复制失败:', err);
        showToast('复制失败', 'error');
    });
}

function showToast(message, type = 'info') {
    // 移除已存在的 toast
    const existingToast = document.querySelector('.toast');
    if (existingToast) {
        existingToast.remove();
    }

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    
    document.body.appendChild(toast);
    
    setTimeout(() => {
        toast.style.animation = 'toastSlideIn 0.3s ease-out reverse';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// 暴露全局函数供 HTML 调用
window.useSuggestion = useSuggestion;
window.copyCode = copyCode;

/**
 * HTML 转义工具函数
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
