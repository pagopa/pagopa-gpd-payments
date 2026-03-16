const axios = require("axios");

if (process.env.canary) {
    axios.defaults.headers.common["X-CANARY"] = "canary"; // for all requests
}

axios.interceptors.request.use(config => {
    // Keep caller timeout if explicitly set; otherwise use default 10s
    if (config.timeout == null) {
        config.timeout = 10000;
    }
    return config;
});

axios.interceptors.response.use(
    response => response,
    error => {
        if (error.code === "ECONNABORTED" && error.message?.includes("timeout")) {
            console.log(`Request timed out: ${error.config?.method?.toUpperCase()} ${error.config?.url}`);
        }
        return Promise.reject(error);
    }
);

function handleAxiosError(error) {
    // Timeout / network / no-response errors must be rethrown
    if (!error.response) {
        throw error;
    }

    // Preserve current behavior for HTTP errors like 400/404/409/500
    return error.response;
}

function get(url, config) {
    return axios.get(url, config)
        .then(res => res)
        .catch(handleAxiosError);
}

function post(url, body, config) {
    return axios.post(url, body, config)
        .then(res => res)
        .catch(handleAxiosError);
}

function put(url, body, config) {
    return axios.put(url, body, config)
        .then(res => res)
        .catch(handleAxiosError);
}

function del(url, config) {
    return axios.delete(url, config)
        .then(res => res)
        .catch(handleAxiosError);
}

module.exports = { get, post, put, del };