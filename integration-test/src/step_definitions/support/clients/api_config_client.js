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

function readCreditorInstitutionIbans(orgId) {
    return get(api_config_host + `/creditorinstitutions/${orgId}/ibans`, {
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

function readStation(stationId) {    
    return get(api_config_host + `/stations/${stationId}`, {
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

module.exports = {
    apiConfigHealthCheck,
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readCreditorInstitutionIbans,
    readECStationAssociation,
    readStation
}
