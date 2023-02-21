const {get, post, del} = require("./common");
const fs = require("fs");

const api_config_host = process.env.api_config_host;

function apiConfigHealthCheck() {    
    return get(api_config_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

function readCreditorInstitution(orgId) {    
    return get(api_config_host + `/creditorinstitutions/${orgId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

function readCreditorInstitutionBroker(brokerId) {    
    return get(api_config_host + `/brokers/${brokerId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

function readStation(stationId, orgId) {    
    return get(api_config_host + `/stations/${stationId}/creditorinstitutions/${orgId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

function createStation(body) {    
    return post(api_config_host + `/stations`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY,
            "Content-Type": "application/json"
        }
    })
}

function createECStationAssociation(orgId, body) {    
    return post(api_config_host + `/creditorinstitutions/${orgId}/stations`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY,
            "Content-Type": "application/json"
        }
    })
}

function deleteStation(stationId) {    
    return del(api_config_host + `/stations/${stationId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

function deleteECStationRelation(orgId, stationId) {    
    return del(api_config_host + `/creditorinstitutions/${orgId}/stations/${stationId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    apiConfigHealthCheck,
    createECStationAssociation,
    createStation,
    deleteECStationRelation,
    deleteStation,
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readStation,
}