const axios = require("axios");
const fs = require('fs');

function get(url, config) {
    return axios.get(url, config)
         .then(res => {
             return res;
         })
         .catch(error => {
             return error;
         });
}

function post(url, body, config) {
    return axios.post(url, body, config)
        .then(res => {
            return res;
        })
        .catch(error => {
            console.log(error);
            return error;
        });
}

function put(url, body) {
    return axios.put(url, body)
        .then(res => {
            return res;
        })
        .catch(error => {
            return error;
        });
}


function del(url, config) {
    return axios.delete(url, config)
        .then(res => {
            return res;
        })
        .catch(error => {
            return error;
        });
}

module.exports = {get, post, put, del}
