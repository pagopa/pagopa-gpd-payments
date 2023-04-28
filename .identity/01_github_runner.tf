module "github_runner_aks" {
  source = "git::https://github.com/pagopa/github-actions-tf-modules.git//app-github-runner-creator?ref=main"

  app_name = local.aks_name

  subscription_id = data.azurerm_subscription.current.id

  github_org              = local.github.org
  github_repository       = local.github.repository
  github_environment_name = var.env

  container_app_github_runner_env_rg = "${local.runner}-github-runner-rg"
}
