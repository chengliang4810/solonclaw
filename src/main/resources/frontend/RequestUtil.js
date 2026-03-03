/**
 * SolonClaw HTTP 请求工具类
 * 统一的 API 请求封装，支持请求拦截、响应处理、错误提示等功能
 */

// ==================== 配置 ====================
const RequestConfig = {
    // API 基础地址
    apiBase: 'http://localhost:12345/api',

    // 默认请求超时时间（毫秒）
    timeout: 120000,

    // 默认请求头
    headers: {
        'Content-Type': 'application/json'
    },

    // 是否显示错误提示
    showError: true,

    // 是否显示加载提示
    showLoading: false
};

// ==================== 工具类 ====================
const RequestUtil = (function () {
    // 请求拦截器
    const requestInterceptors = [];
    // 响应拦截器
    const responseInterceptors = [];

    /**
     * 构建完整 URL
     * @param {string} url - 请求路径
     * @param {object} params - 查询参数
     * @returns {string} 完整 URL
     */
    function buildUrl(url, params = {}) {
        // 如果是完整 URL，直接返回
        if (url.startsWith('http://') || url.startsWith('https://')) {
            let fullUrl = url;
            if (Object.keys(params).length > 0) {
                fullUrl += '?' + new URLSearchParams(params).toString();
            }
            return fullUrl;
        }

        // 拼接基础路径
        let fullUrl = RequestConfig.apiBase + url;

        // 添加查询参数
        if (Object.keys(params).length > 0) {
            fullUrl += '?' + new URLSearchParams(params).toString();
        }

        return fullUrl;
    }

    /**
     * 合并请求头
     * @param {object} customHeaders - 自定义请求头
     * @returns {object} 合并后的请求头
     */
    function mergeHeaders(customHeaders = {}) {
        return {
            ...RequestConfig.headers,
            ...customHeaders
        };
    }

    /**
     * 显示错误提示
     * @param {string} message - 错误消息
     * @param {string} type - 消息类型
     */
    function showError(message, type = 'error') {
        if (!RequestConfig.showError) return;

        // 查找 toast 元素
        const toast = document.getElementById('toast');
        if (toast) {
            const bgColors = {
                success: 'bg-green-500',
                error: 'bg-red-500',
                info: 'bg-blue-500',
                warning: 'bg-yellow-500',
            };

            toast.className = `fixed top-4 right-4 px-4 py-3 rounded-lg shadow-lg z-50 transition-all duration-300 ${bgColors[type]} text-white`;
            toast.textContent = message;
            toast.classList.remove('hidden');

            setTimeout(() => {
                toast.classList.add('hidden');
            }, 3000);
        } else {
            console.error(`[${type.toUpperCase()}]`, message);
        }
    }

    /**
     * 处理响应
     * @param {Response} response - Fetch 响应对象
     * @param {boolean} showError - 是否显示错误提示
     * @returns {Promise} 处理后的数据
     */
    async function handleResponse(response, showError = true) {
        let data;

        // 尝试解析 JSON
        try {
            const text = await response.text();
            data = text ? JSON.parse(text) : {};
        } catch (e) {
            data = { message: '响应解析失败' };
        }

        // 执行响应拦截器
        for (const interceptor of responseInterceptors) {
            const result = interceptor(response, data);
            if (result !== undefined) {
                data = result;
            }
        }

        // 检查 HTTP 状态码
        if (!response.ok) {
            const errorMsg = data.message || `请求失败: ${response.status} ${response.statusText}`;

            if (showError) {
                showError(errorMsg, 'error');
            }

            throw new RequestError(response.status, errorMsg, data);
        }

        // 检查业务状态码
        if (data.code !== undefined && data.code !== 200) {
            const errorMsg = data.message || '业务处理失败';

            if (showError) {
                showError(errorMsg, 'error');
            }

            throw new RequestError(data.code, errorMsg, data);
        }

        // 返回 data 字段（如果存在）或整个响应对象
        return data.data !== undefined ? data.data : data;
    }

    /**
     * 请求错误类
     */
    class RequestError extends Error {
        constructor(code, message, data = null) {
            super(message);
            this.name = 'RequestError';
            this.code = code;
            this.data = data;
        }
    }

    /**
     * 核心请求方法
     * @param {string} url - 请求地址
     * @param {object} options - 请求选项
     * @returns {Promise} 请求结果
     */
    async function request(url, options = {}) {
        const {
            method = 'GET',
            data = null,
            params = {},
            headers = {},
            timeout = RequestConfig.timeout,
            signal = null,
            showError = RequestConfig.showError,
            retries = 0
        } = options;

        // 构建 URL
        const fullUrl = buildUrl(url, params);

        // 合并请求头
        const mergedHeaders = mergeHeaders(headers);

        // 构建请求配置
        const config = {
            method: method,
            headers: mergedHeaders
        };

        // 添加请求体
        if (data) {
            if (mergedHeaders['Content-Type'] === 'application/json') {
                config.body = JSON.stringify(data);
            } else if (mergedHeaders['Content-Type'] === 'application/x-www-form-urlencoded') {
                config.body = new URLSearchParams(data).toString();
            } else {
                config.body = data;
            }
        }

        // 处理请求取消
        let abortController = null;
        let timeoutId = null;

        if (signal) {
            config.signal = signal;
        } else if (timeout > 0) {
            abortController = new AbortController();
            config.signal = abortController.signal;
            timeoutId = setTimeout(() => abortController.abort(), timeout);
        }

        // 执行请求拦截器
        for (const interceptor of requestInterceptors) {
            const result = interceptor(config);
            if (result !== undefined) {
                Object.assign(config, result);
            }
        }

        // 重试逻辑
        let lastError = null;
        let attempt = 0;

        while (attempt <= retries) {
            try {
                const response = await fetch(fullUrl, config);

                if (timeoutId) {
                    clearTimeout(timeoutId);
                }

                return await handleResponse(response, showError);
            } catch (error) {
                lastError = error;

                // 如果是 AbortError（超时或取消），不重试
                if (error.name === 'AbortError') {
                    const errorMsg = timeout > 0 ? '请求超时' : '请求已取消';
                    if (showError) {
                        showError(errorMsg, 'error');
                    }
                    throw new RequestError(0, errorMsg);
                }

                // 如果是 RequestError（业务错误），不重试
                if (error instanceof RequestError) {
                    throw error;
                }

                // 网络错误，重试
                attempt++;
                if (attempt <= retries) {
                    await new Promise(resolve => setTimeout(resolve, 1000 * attempt));
                }
            }
        }

        // 重试失败
        if (showError) {
            showError('网络请求失败，请检查网络连接', 'error');
        }
        throw lastError || new RequestError(0, '请求失败');
    }

    // ==================== 公开方法 ====================

    return {
        /**
         * GET 请求
         * @param {string} url - 请求地址
         * @param {object} options - 请求选项
         * @returns {Promise} 请求结果
         */
        get: function (url, options = {}) {
            return request(url, { ...options, method: 'GET' });
        },

        /**
         * POST 请求
         * @param {string} url - 请求地址
         * @param {object} data - 请求数据
         * @param {object} options - 请求选项
         * @returns {Promise} 请求结果
         */
        post: function (url, data = null, options = {}) {
            return request(url, { ...options, method: 'POST', data });
        },

        /**
         * PUT 请求
         * @param {string} url - 请求地址
         * @param {object} data - 请求数据
         * @param {object} options - 请求选项
         * @returns {Promise} 请求结果
         */
        put: function (url, data = null, options = {}) {
            return request(url, { ...options, method: 'PUT', data });
        },

        /**
         * DELETE 请求
         * @param {string} url - 请求地址
         * @param {object} options - 请求选项
         * @returns {Promise} 请求结果
         */
        delete: function (url, options = {}) {
            return request(url, { ...options, method: 'DELETE' });
        },

        /**
         * 通用请求方法
         * @param {string} url - 请求地址
         * @param {object} options - 请求选项
         * @returns {Promise} 请求结果
         */
        request: request,

        /**
         * SSE 流式请求
         * @param {string} url - 请求地址
         * @param {object} data - 请求数据
         * @param {function} onChunk - 接收数据块的回调
         * @param {function} onError - 错误回调
         * @param {function} onComplete - 完成回调
         * @param {object} options - 请求选项
         * @returns {object} 包含 abort 方法的控制对象
         */
        stream: function (url, data, onChunk, onError, onComplete, options = {}) {
            const {
                timeout = RequestConfig.timeout,
                headers = {}
            } = options;

            const abortController = new AbortController();
            let timeoutId = null;

            if (timeout > 0) {
                timeoutId = setTimeout(() => {
                    abortController.abort();
                    if (onError) {
                        onError(new Error('请求超时'));
                    }
                }, timeout);
            }

            const fullUrl = buildUrl(url);

            fetch(fullUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'text/event-stream',
                    ...headers
                },
                body: JSON.stringify(data),
                signal: abortController.signal
            })
                .then(async (response) => {
                    if (timeoutId) {
                        clearTimeout(timeoutId);
                    }

                    if (!response.ok) {
                        throw new Error(`HTTP error! status: ${response.status}`);
                    }

                    const reader = response.body.getReader();
                    const decoder = new TextDecoder();
                    let buffer = '';

                    while (true) {
                        const { done, value } = await reader.read();

                        if (done) break;

                        buffer += decoder.decode(value, { stream: true });
                        const lines = buffer.split('\n');
                        buffer = lines.pop() || '';

                        for (const line of lines) {
                            if (line.startsWith('data:')) {
                                const data = line.substring(5).trim();
                                if (data) {
                                    try {
                                        const event = JSON.parse(data);
                                        if (onChunk) {
                                            onChunk(event);
                                        }
                                    } catch (e) {
                                        console.warn('解析 SSE 数据失败:', data);
                                    }
                                }
                            }
                        }
                    }

                    if (onComplete) {
                        onComplete();
                    }
                })
                .catch((error) => {
                    if (error.name === 'AbortError') {
                        if (onError) {
                            onError(new Error('请求已取消'));
                        }
                    } else {
                        if (onError) {
                            onError(error);
                        }
                    }
                });

            return {
                abort: () => {
                    if (timeoutId) {
                        clearTimeout(timeoutId);
                    }
                    abortController.abort();
                }
            };
        },

        /**
         * 添加请求拦截器
         * @param {function} interceptor - 拦截器函数
         */
        addRequestInterceptor: function (interceptor) {
            if (typeof interceptor === 'function') {
                requestInterceptors.push(interceptor);
            }
        },

        /**
         * 添加响应拦截器
         * @param {function} interceptor - 拦截器函数
         */
        addResponseInterceptor: function (interceptor) {
            if (typeof interceptor === 'function') {
                responseInterceptors.push(interceptor);
            }
        },

        /**
         * 设置配置
         * @param {object} config - 配置对象
         */
        setConfig: function (config) {
            Object.assign(RequestConfig, config);
        },

        /**
         * 获取配置
         * @returns {object} 当前配置
         */
        getConfig: function () {
            return { ...RequestConfig };
        },

        /**
         * 导出 RequestError 类
         */
        RequestError: RequestError
    };
})();

// ==================== 导出 ====================
// 如果支持模块化，导出模块
if (typeof module !== 'undefined' && module.exports) {
    module.exports = RequestUtil;
}
