package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.ScopeApproval;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScopeApprovalRepository extends JpaRepository<ScopeApproval, Long> {

    List<ScopeApproval> findByChangeNoOrderByVotedAtAsc(String changeNo);

    List<ScopeApproval> findByChangeNoAndVoteTypeOrderByVotedAtAsc(
            String changeNo, com.chris64233.cc.legalhold.domain.ApprovalVoteType voteType);

    boolean existsByChangeNoAndReviewerAndVoteType(
            String changeNo, String reviewer,
            com.chris64233.cc.legalhold.domain.ApprovalVoteType voteType);
}
