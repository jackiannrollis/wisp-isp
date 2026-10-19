package com.corebalt.isp.customers.application;

import com.corebalt.isp.customers.api.CustomerDto;
import com.corebalt.isp.customers.domain.Customer;
import org.mapstruct.Mapper;

@Mapper
interface CustomerMapper {

    CustomerDto toDto(Customer customer);
}
