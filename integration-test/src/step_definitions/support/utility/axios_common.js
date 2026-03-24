const axios = require("axios");
const https = require("https");


const ENABLE_DEBUG = process.env.ENABLE_DEBUG === "true";

if (process.env.canary) {
    axios.defaults.headers.common["X-CANARY"] = "canary";
}


// This flag should stay disabled by default and only be enabled where strictly necessary (e.g. in local environment with self-signed certificates)
const allowSelfSignedCert =
    process.env.ALLOW_SELF_SIGNED_CERT === "true" ||
    process.env.NODE_TLS_REJECT_UNAUTHORIZED === "0";

const insecureHttpsAgent = new https.Agent({
    rejectUnauthorized: !allowSelfSignedCert
});

axios.interceptors.request.use(config => {
    if (config.timeout == null) {
        config.timeout = 10000;
    }

    config.httpsAgent = insecureHttpsAgent;

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
    if (!error.response) {
        console.log("[axios_common] network/no-response error:", {
            message: error.message,
            code: error.code,
            method: error.config?.method,
            url: error.config?.url
        });
        throw error;
    }

	if (ENABLE_DEBUG) {
	    console.log("[axios_common] http error response:", {
	        status: error.response?.status,
	        statusText: error.response?.statusText,
	        method: error.config?.method,
	        url: error.config?.url,
	        responseHeaders: error.response?.headers,
	        responseData: error.response?.data
	    });
	}

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