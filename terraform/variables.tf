variable "region"{
  type=string
  description = "Project Region"
  default = "us-central1"
}

variable "zone"{
  type=string
  description = "Project Zone"
  default = "us-central1-a"
}

variable "project_id"{
  type=string
  description = "Project Id"
  default = "tf-trials-323004"
}

variable "create_vm_instances"{
  type=bool
  description = "Toggle for VM Instance creation"
  default = false
}