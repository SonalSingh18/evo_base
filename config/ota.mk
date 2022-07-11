ifeq ($(EVO_BUILD_TYPE), CUSTOM)

EVO_OTA_VERSION_CODE := twelve

PRODUCT_PRODUCT_PROPERTIES += \
    org.evolution.ota.version_code=$(EVO_OTA_VERSION_CODE)

PRODUCT_PACKAGES += \
    Updates

endif
