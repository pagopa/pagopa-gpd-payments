const {get, post, del} = require("../utility/axios_common");
const fs = require("fs");

const api_config_host = process.env.api_config_host;

function apiConfigHealthCheck() {    
    return get(api_config_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function readCreditorInstitution(orgId) {    
    return get(api_config_host + `/creditorinstitutions/${orgId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function createCreditorInstitution(body) {    
    return post(api_config_host + `/creditorinstitutions`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}


function readCreditorInstitutionIbans(orgId) {
    return get(api_config_host + `/creditorinstitutions/${orgId}/ibans/enhanced`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function createCreditorInstitutionIbans(orgId, body) {
    return post(api_config_host + `/creditorinstitutions/${orgId}/ibans`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function readCreditorInstitutionBroker(brokerId) {    
    return get(api_config_host + `/brokers/${brokerId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function createCreditorInstitutionBroker(body) {    
    return post(api_config_host + `/brokers`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function readStation(stationId) {    
    return get(api_config_host + `/stations/${stationId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function createStation(body) {    
    return post(api_config_host + `/stations`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function readECStationAssociation(stationId, orgId) {    
    return get(api_config_host + `/stations/${stationId}/creditorinstitutions/${orgId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function createECStationAssociation(orgId, body) {    
    return post(api_config_host + `/creditorinstitutions/${orgId}/stations`, body, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function refreshConfig() {    
    return get(api_config_host + `/refresh/config`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

module.exports = {
    apiConfigHealthCheck,
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readCreditorInstitutionIbans,
    readECStationAssociation,
    readStation,
    createCreditorInstitution,
    createCreditorInstitutionIbans,
    createCreditorInstitutionBroker,
    createStation,
    createECStationAssociation,
    refreshConfig
}
