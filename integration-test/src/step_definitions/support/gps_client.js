const {post, get, put, del} = require("./common");
const fs = require("fs");

const gps_host = process.env.gps_host;

function gpsHealthCheck() {
    return get(gps_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function createOrganization(idOrg, body) {
    return post(gps_host + `/organizations/${idOrg}`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function deleteOrganization(idOrg) {
    return del(gps_host + `/organizations/${idOrg}`, {
       headers: {
           "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
       }
    })
}

function createService(body) {
    return post(gps_host + `/services`, body, {
       headers: {
           "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
       }
    })
}

function deleteService(serviceId) {
    return del(gps_host + `/services/${serviceId}`, {
       headers: {
           "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
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
