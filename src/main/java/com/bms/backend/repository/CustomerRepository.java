package com.bms.backend.repository;

import com.bms.backend.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer , Long> {
    Customer findByName(String name);
}