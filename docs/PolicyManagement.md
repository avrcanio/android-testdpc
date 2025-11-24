# Policy Management Screen (Qbit DPC)

Ovaj dokument opisuje sve preference sekcije i funkcije koje se prikazuju na glavnom Policy Management ekranu (`PolicyManagementActivity` → `PolicyManagementFragment`). Naslovi su string resursi iz `device_policy_header.xml`, a stavke su preference s pripadajućim ključevima.

## Sekcije i stavke

- **@string/accessibility_title**
  - `set_accessibility_services` (@string/set_accessibility_services)

- **@string/set_time_manage**
  - `set_time` (@string/set_time)
  - `set_time_zone` (@string/set_time_zone)

- **@string/set_system_setting**
  - `set_screen_brightness` (@string/set_screen_brightness)
  - `auto_brightness` (@string/auto_brightness)
  - `set_screen_off_timeout` (@string/set_screen_off_timeout)
  - `set_profile_name` (@string/set_profile_name)
  - `set_organization_id` (@string/set_organization_id)

- **@string/telephony_title**
  - `manage_override_apn` (@string/manage_override_apn)
  - `manage_esim` (@string/manage_esim)

- **@string/cross_profile_calendar**
  - `cross_profile_calendar` (@string/cross_profile_calendar)

- **@string/account_management_title**
  - `set_disable_account_management` (@string/set_disable_account_management)
  - `get_disable_account_management` (@string/get_disable_account_management)
  - `add_account` (@string/add_account)
  - `remove_account` (@string/remove_account)

- **@string/apps_management_title**
  - `enable_system_apps` (@string/enable_system_apps_title)
  - `enable_system_apps_by_package_name` (@string/enable_system_apps_by_package_name)
  - `enable_system_apps_by_intent` (@string/enable_system_apps_by_intent)
  - `install_existing_packages` (@string/install_existing_packages_title)
  - `install_apk_package` (@string/install_apk_package_title)
  - `uninstall_package` (@string/uninstall_packages_title)
  - `hide_apps` (@string/hide_apps_title)
  - `hide_apps_parent` (@string/hide_apps_parent_title)
  - `unhide_apps` (@string/unhide_apps_title)
  - `unhide_apps_parent` (@string/unhide_apps_parent_title)
  - `suspend_apps` (@string/suspend_apps_title)
  - `unsuspend_apps` (@string/unsuspend_apps_title)
  - `clear_app_data` (@string/clear_app_data_title)
  - `keep_uninstalled_packages` (@string/keep_uninstalled_packages)
  - `managed_configurations` (@string/managed_configurations)
  - `disable_metered_data` (@string/metered_data_restriction)
  - `app_feedback_notifications` (@string/app_feedback_notifications)

- **@string/delegation_title**
  - `app_restrictions_managing_package` (@string/app_restrictions_managing_package)
  - `manage_cert_installer` (@string/manage_cert_installer)
  - `generic_delegation` (@string/generic_delegation)

- **@string/block_uninstallation_title**
  - `block_uninstallation_by_pkg` (@string/block_uninstallation_by_pkg)
  - `block_uninstallation_list` (@string/block_uninstallation_list)

- **@string/camera_title**
  - `disable_camera` (@string/disable_camera)
  - `disable_camera_on_parent` (@string/disable_camera_on_parent)
  - `capture_image` (@string/capture_image)
  - `capture_video` (@string/capture_video)
  - `disable_screen_capture` (@string/disable_screen_capture)
  - `disable_screen_capture_on_parent` (@string/disable_screen_capture_on_parent)
  - `mute_audio` (@string/mute_audio)

- **@string/certificate_management_title**
  - `request_manage_credentials` (@string/request_manage_credentials)
  - `install_key_certificate` (@string/install_key_certificate)
  - `remove_key_certificate` (@string/remove_key_certificate)
  - `override_key_selection` (@string/key_override_alias)
  - `generate_key_and_certificate` (@string/generate_key_and_certificate)
  - `test_key_usability` (@string/test_key_usage)
  - `install_ca_certificate` (@string/install_ca_certificate)
  - `get_ca_certificates` (@string/get_ca_certificates)
  - `remove_all_ca_certificates` (@string/remove_all_ca_certificates)
  - `grant_key_pair_to_app` (@string/grant_key_pair_to_app)

- **@string/wifi_management_title**
  - `create_wifi_configuration` (@string/create_wifi_configuration)
  - `create_eap_tls_wifi_configuration` (@string/create_eap_tls_wifi_configuration)
  - `enable_wifi_config_lockdown` (@string/enable_wifi_config_lockdown)
  - `modify_wifi_configuration` (@string/modify_wifi_configuration)
  - `modify_owned_wifi_configuration` (@string/modify_owned_wifi_configuration)
  - `remove_not_owned_wifi_configurations` (@string/remove_not_owned_wifi_configurations)
  - `show_wifi_mac_address` (@string/show_wifi_mac_address)
  - `set_wifi_min_security_level` (@string/set_wifi_min_security_level)
  - `set_wifi_ssid_restriction` (@string/set_wifi_ssid_restriction)

- **@string/input_methods_title**
  - `set_input_methods` (@string/set_input_methods)
  - `set_input_methods_on_parent` (@string/set_input_methods_on_parent)

- **@string/notification_listeners_title**
  - `set_notification_listeners` (@string/set_notification_listeners)
  - `set_notification_listeners_text` (@string/set_notification_listeners_text)

- **@string/lock_category**
  - `password_complexity` (@string/password_complexity_title)
  - `required_password_complexity` (@string/required_password_complexity_title)
  - `password_compliant` (@string/password_compliant_title)
  - `separate_challenge` (@string/separate_challenge_title)
  - `lock_screen_policy` (@string/lock_screen_policy)
  - `password_constraints` (@string/password_constraints)
  - `reset_password` (@string/reset_password)
  - `lock_now` (@string/lock_now)
  - `set_new_password` (@string/request_to_set_new_password)
  - `set_new_password_with_complexity` (@string/request_to_set_new_password_with_complexity)
  - `set_required_password_complexity` (@string/set_required_password_complexity)
  - `set_required_password_complexity_on_parent` (@string/set_required_password_complexity_on_parent)
  - `set_profile_parent_new_password` (@string/request_to_set_profile_parent_new_password)
  - `set_profile_parent_new_password_device_requirement` (@string/request_to_set_profile_parent_new_password_device_requirement)

- **@string/lock_task_title**
  - `manage_lock_task` (@string/manage_lock_task)
  - `check_lock_task_permitted` (@string/check_lock_task_permitted)
  - `set_lock_task_features` (@string/set_lock_task_features_title)
  - `start_lock_task` (@string/start_lock_task)
  - `relaunch_in_lock_task` (@string/relaunch_in_lock_task_title)
  - `stop_lock_task` (@string/stop_lock_task)

- **@string/managed_profile_specific_policy_category_title**
  - `managed_profile_policies` (@string/managed_profile_specific_policy_title)

- **@string/bind_device_admin_category_title**
  - `bind_device_admin_policies` (@string/bind_device_admin_policy_title)

- **@string/networking_management_title**
  - `network_stats` (@string/network_stats)
  - `set_always_on_vpn` (@string/set_always_on_vpn)
  - `set_get_preferential_network_service_status` (@string/set_get_preferential_network_service_status)
  - `enterprise_slice` (@string/enterprise_slice)
  - `set_global_http_proxy` (@string/set_global_http_proxy)
  - `clear_global_http_proxy` (@string/clear_global_http_proxy)
  - `set_private_dns_mode` (@string/set_global_private_dns)

- **@string/permission_management**
  - `set_permission_policy` (@string/set_default_permission_policy)
  - `manage_app_permissions` (@string/manage_app_permissions)

- **@string/single_use_devices**
  - `disable_status_bar` (@string/disable_status_bar)
  - `reenable_status_bar` (@string/reenable_status_bar)
  - `disable_keyguard` (@string/disable_keyguard)
  - `reenable_keyguard` (@string/reenable_keyguard)
  - `start_kiosk_mode` (@string/start_kiosk_mode)

- **@string/system_update_management**
  - `system_update_policy` (@string/system_update_policy)
  - `system_update_pending` (@string/system_update_pending)
  - `managed_system_updates` (@string/install_update)

- **@string/user_management**
  - `create_managed_profile` (@string/create_managed_profile)
  - `create_and_manage_user` (@string/create_and_manage_user)
  - `remove_user` (@string/remove_user)
  - `switch_user` (@string/switch_user)
  - `start_user_in_background` (@string/start_user_in_background)
  - `stop_user` (@string/stop_user)
  - `logout_user` (@string/logout_user)
  - `enable_logout` (@string/enable_logout)
  - `set_user_session_message` (@string/set_user_session_message)
  - `set_affiliation_ids` (@string/manage_affiliation_ids)
  - `affiliated_user` (@string/affiliated_user)
  - `ephemeral_user` (@string/ephemeral_user)

- **@string/user_restrictions_management_title**
  - `set_user_restrictions` (@string/user_restrictions_preference_title)
  - `set_user_restrictions_parent` (@string/user_restrictions_parent_preference_title)

- **@string/settings_management_title**
  - `stay_on_while_plugged_in` (@string/stay_on_while_plugged_in)
  - `install_nonmarket_apps` (@string/install_non_market_apps)
  - `set_location_enabled` (@string/set_location_enabled)
  - `set_location_mode` (@string/set_location_mode)

- **@string/support_messages**
  - `set_short_support_message` (@string/set_short_support_message)
  - `set_long_support_message` (@string/set_long_support_message)

- **@string/device_owner_management**
  - `set_device_organization_name` (@string/set_organization_name)
  - `set_auto_time_required` (@string/set_auto_time_required)
  - `set_auto_time` (@string/set_auto_time)
  - `set_auto_time_zone` (@string/set_auto_time_zone)
  - `enable_security_logging` (@string/enable_security_logging)
  - `request_security_logs` (@string/request_security_logs)
  - `request_pre_reboot_security_logs` (@string/request_pre_reboot_security_logs)
  - `enable_network_logging` (@string/enable_network_logging)
  - `request_network_logs` (@string/request_network_logs)
  - `request_bugreport` (@string/request_bugreport)
  - `enable_backup_service` (@string/enable_backup_service)
  - `common_criteria_mode` (@string/common_criteria_mode)
  - `enable_usb_data_signaling` (@string/enable_usb_data_signaling)
  - `remove_device_owner` (@string/remove_device_owner)
  - `reboot` (@string/reboot)
  - `set_factory_reset_protection_policy` (@string/set_factory_reset_protection_policy)
  - `suspend_personal_apps` (@string/suspend_personal_apps)
  - `profile_max_time_off` (@string/profile_max_time_off)

- **@string/data_wipe_section**
  - `remove_managed_profile` (@string/remove_managed_profile)
  - `factory_reset_device` (@string/factory_reset_device)

- **@string/transfer_ownership**
  - `transfer_ownership_to_component` (@string/transfer_ownership_to_component)

- **@string/cross_profile_section**
  - `cross_profile_apps` (@string/cross_profile_apps_api)
  - `cross_profile_apps_allowlist` (@string/cross_profile_apps_allowlist)

- **@string/nearby_streaming_section**
  - `nearby_notification_streaming` (@string/nearby_notification_streaming)
  - `nearby_app_streaming` (@string/nearby_app_streaming)

- **@string/mte_section**
  - `mte_policy` (@string/mte_policy)

- **@string/credential_manager_title**
  - `credential_manager_set_allowlist` (@string/credential_manager_set_allowlist)
  - `credential_manager_set_allowlist_and_system` (@string/credential_manager_set_allowlist_and_system)
  - `credential_manager_set_blocklist` (@string/credential_manager_set_blocklist)
  - `credential_manager_clear_policy` (@string/credential_manager_clear_policy)

## Reference
- Izvor: `src/main/res/xml/device_policy_header.xml`
- Aktivnost/fragment: `PolicyManagementActivity` → `PolicyManagementFragment`

## Qubit enrol telemetry (POST payload)
- Enrol API, uz enrol_token, �alje i: is_device_owner (Util), os_version, sdk_int, device_model, device_manufacturer. Implementirano u EnrolApiClient.enrolWithSavedToken().
