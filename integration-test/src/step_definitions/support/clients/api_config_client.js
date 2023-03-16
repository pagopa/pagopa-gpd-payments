const {get, post, del} = require("../utility/axios_common");
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

function readCreditorInstitutionIbans(orgId) {
    return get(api_config_host + `/creditorinstitutions/${orgId}/ibans`, {
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

function readStation(stationId) {    
    return get(api_config_host + `/stations/${stationId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

function readECStationAssociation(stationId, orgId) {    
    return get(api_config_host + `/stations/${stationId}/creditorinstitutions/${orgId}`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.APICONFIG_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    apiConfigHealthCheck,
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readCreditorInstitutionIbans,
    readECStationAssociation,
    readStation
}