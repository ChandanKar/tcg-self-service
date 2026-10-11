package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ChatChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Chat channels (E24-T02). */
public interface ChatChannelRepository extends JpaRepository<ChatChannel, String> {

    List<ChatChannel> findByEnvironmentIdAndIsActiveTrue(String environmentId);

    List<ChatChannel> findByEnvironmentIdIsNullAndIsActiveTrue();

    List<ChatChannel> findByEnvironmentIdOrderByLabelAsc(String environmentId);

    List<ChatChannel> findByEnvironmentIdIsNullOrderByLabelAsc();

    List<ChatChannel> findAllByOrderByLabelAsc();
}
