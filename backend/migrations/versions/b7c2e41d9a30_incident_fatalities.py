"""incident fatalities

Revision ID: b7c2e41d9a30
Revises: 1650f0489d77
Create Date: 2026-10-31 09:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b7c2e41d9a30'
down_revision: Union[str, None] = '1650f0489d77'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('incidents', sa.Column('fatalities', sa.String(length=8), nullable=True))


def downgrade() -> None:
    op.drop_column('incidents', 'fatalities')
