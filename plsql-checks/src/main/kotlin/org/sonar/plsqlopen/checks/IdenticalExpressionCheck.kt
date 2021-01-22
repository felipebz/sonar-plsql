/**
 * Z PL/SQL Analyzer
 * Copyright (C) 2015-2021 Felipe Zorzo
 * mailto:felipebzorzo AT gmail DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plsqlopen.checks

import com.sonar.sslr.api.AstNode
import org.sonar.plugins.plsqlopen.api.ConditionsGrammar
import org.sonar.plugins.plsqlopen.api.PlSqlGrammar
import org.sonar.plugins.plsqlopen.api.annotations.*

@Rule(key = IdenticalExpressionCheck.CHECK_KEY, priority = Priority.BLOCKER, tags = [Tags.BUG])
@ConstantRemediation("2min")
@RuleInfo(scope = RuleInfo.Scope.ALL)
@ActivatedByDefault
class IdenticalExpressionCheck : AbstractBaseCheck() {

    override fun init() {
        subscribeTo(PlSqlGrammar.COMPARISON_EXPRESSION)
    }

    override fun visitNode(node: AstNode) {
        val operator = node.getFirstChild(ConditionsGrammar.RELATIONAL_OPERATOR)
        if (operator != null) {

            val leftSide = node.firstChild
            val rightSide = node.lastChild

            if (CheckUtils.equalNodes(leftSide, rightSide)) {
                addIssue(leftSide, getLocalizedMessage(CHECK_KEY), operator.tokenValue)
                        .secondary(rightSide, "Original")
            }

        }
    }

    companion object {
        internal const val CHECK_KEY = "IdenticalExpression"
    }

}
