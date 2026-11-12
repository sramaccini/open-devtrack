/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GroupMemberRepository extends JpaRepository<GroupMemberEntity, GroupMemberEntity.Key> {

    List<GroupMemberEntity> findByGroupIdOrderByUsername(UUID groupId);

    List<GroupMemberEntity> findByGroupIdIn(Collection<UUID> groupIds);

    List<GroupMemberEntity> findByUsername(String username);

    long countByGroupId(UUID groupId);
}
