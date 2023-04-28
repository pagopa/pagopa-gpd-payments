const {post, get, put, del} = require("../utility/axios_common");
const fs = require("fs");

const gps_host = process.env.gps_host;

function gpsHealthCheck() {
    return get(gps_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function createOrganization(idOrg, body) {
    return post(gps_host + `/organizations/${idOrg}`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function deleteOrganization(idOrg) {
    return del(gps_host + `/organizations/${idOrg}`, {
       headers: {
           "Ocp-Apim-Subscription-Key": process.env.SUBKEY
       }
    })
}

function createService(body) {
    return post(gps_host + `/services`, body, {
       headers: {
           "Ocp-Apim-Subscription-Key": process.env.SUBKEY
       }
    })
}

function deleteService(serviceId) {
    return del(gps_host + `/services/${serviceId}`, {
       headers: {
           "Ocp-Apim-Subscription-Key": process.env.SUBKEY
       }
    })
}


module.exports = {
    gpsHealthCheck,
    createOrganization,
    deleteOrganization,
    createService,
    deleteService
}
