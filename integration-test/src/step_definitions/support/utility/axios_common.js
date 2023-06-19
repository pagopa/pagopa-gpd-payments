const axios = require("axios");
const fs = require('fs');

if (process.env.canary) {
    axios.defaults.headers.common['X-CANARY'] = 'canary' // for all requests
}

axios.interceptors.request.use(config => {
  config.timeout = 10000; // Wait for 10 seconds before timing out
  return config;
});

axios.interceptors.response.use(
  response => response,
  error => {
    if (error.code === 'ECONNABORTED' && error.message.includes('timeout')) {
      console.log('Request timed out');
    }
    return Promise.reject(error);
  }
);

function get(url, config) {
    return axios.get(url, config)
         .then(res => {
             return res;
         })
         .catch(error => {
             return error.response;
         });
}

function post(url, body, config) {
    return axios.post(url, body, config)
        .then(res => {
            return res;
        })
        .catch(error => {
            return error.response;
        });
}

function put(url, body) {
    return axios.put(url, body)
        .then(res => {
            return res;
        })
        .catch(error => {
            return error.response;
        });
}


function del(url, config) {
    return axios.delete(url, config)
        .then(res => {
            return res;
        })
        .catch(error => {
            return error.response;
        });
}

module.exports = {get, post, put, del}
