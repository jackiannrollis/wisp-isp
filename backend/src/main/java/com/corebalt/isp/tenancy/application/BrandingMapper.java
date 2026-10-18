package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.tenancy.api.BrandingDto;
import com.corebalt.isp.tenancy.domain.Tenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface BrandingMapper {

    @Mapping(target = "primaryColor", source = "brandColor")
    BrandingDto toDto(Tenant tenant);
}
